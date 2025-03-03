package com.reconnect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.reconnect.config.AppConfig;
import com.reconnect.model.ProductLinks;
import com.reconnect.model.ProductResponse;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PlaywrightService implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(PlaywrightService.class);
    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final Page page;
    private static final Path USER_DATA_DIR = Paths.get("browser-data");
    private static final int DEFAULT_TIMEOUT = 60000;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private static final String API_URL = AppConfig.getApiUrl() + "/api/products/find";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public PlaywrightService() {
        logger.info("Initializing PlaywrightService");
        try {
            this.playwright = Playwright.create();
            this.httpClient = new OkHttpClient();
            this.objectMapper = new ObjectMapper();

            logger.debug("Configuring browser options");
            BrowserType.LaunchPersistentContextOptions contextOptions = new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(true)
                    .setViewportSize(1920, 1080);

            logger.info("Launching browser in headless mode");
            this.context = playwright.chromium().launchPersistentContext(USER_DATA_DIR, contextOptions);
            this.browser = context.browser();
            this.page = context.newPage();

            page.setDefaultTimeout(DEFAULT_TIMEOUT);

            logger.debug("Browser setup complete");
            logger.info("PlaywrightService initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize PlaywrightService", e);
            throw e;
        }
    }

    public void navigateTo(String url) {
        page.navigate(url);
    }

    public String getPageTitle() {
        return page.title();
    }

    public List<ProductLinks> extractProductLinks() {
        List<ProductLinks> productLinks = new ArrayList<>();
        int currentPage = 0;
        boolean hasNextPage = true;

        try {
            while (hasNextPage) {
                logger.info("Processing page {}", currentPage);
                String pageUrl = String.format("https://app.dropi.com.br/produtos?&pg=%d", currentPage);
                page.navigate(pageUrl);
                
                // First check for empty products message
                ElementHandle emptyMessage = page.querySelector(
                    "span:text('Ops, você ainda não tem nenhum produto importado')");
                
                if (emptyMessage != null) {
                    logger.info("No more products to process");
                    return productLinks;
                }

                try {
                    page.waitForSelector("tr.dropi--table-row-product",
                            new Page.WaitForSelectorOptions()
                                    .setState(WaitForSelectorState.VISIBLE)
                                    .setTimeout(DEFAULT_TIMEOUT));
                } catch (TimeoutError e) {
                    logger.info("No more pages to process, stopping at page {}", currentPage);
                    break;
                }

                page.waitForTimeout(5000);

                List<ElementHandle> rows = page.querySelectorAll("tr.dropi--table-row-product");
                logger.debug("Found {} product rows on page {}", rows.size(), currentPage);

                if (rows.isEmpty()) {
                    logger.info("No more products found on page {}, stopping pagination", currentPage);
                    hasNextPage = false;
                    break;
                }

                for (ElementHandle row : rows) {
                    try {
                        ElementHandle removedTag = row.querySelector(
                                "span.dropi--tag-red[data-original-title='O anúncio deste produto no Fornecedor foi removido, altere o produto para não exibir em sua loja']");

                        if (removedTag != null) {
                            logger.debug("Skipping removed product");
                            continue;
                        }

                        ElementHandle dropiLink = row.waitForSelector(
                                "a[href^='https://app.dropi.com.br/editar/produto/']",
                                new ElementHandle.WaitForSelectorOptions().setTimeout(5000));

                        ElementHandle aliExpressLink = row.waitForSelector(
                                "a[href^='https://pt.aliexpress.com/item/']",
                                new ElementHandle.WaitForSelectorOptions().setTimeout(5000));

                        if (dropiLink != null && aliExpressLink != null) {
                            productLinks.add(new ProductLinks(
                                    dropiLink.getAttribute("href"),
                                    aliExpressLink.getAttribute("href")));
                        }
                    } catch (TimeoutError e) {
                        logger.error("Timeout while processing row on page {}: {}", currentPage, e.getMessage());
                        continue;
                    }
                }

                currentPage++;
                logger.info("Completed processing page {}, moving to next page", currentPage - 1);
                page.waitForTimeout(5000);
            }
        } catch (TimeoutError e) {
            logger.error("Failed to extract product links", e);
            throw e;
        }

        logger.info("Completed extracting {} product links from {} pages", productLinks.size(), currentPage);
        return productLinks;
    }

    private ProductResponse findProduct(String sku, String aliExpressLink) {
        try {
            Map<String, String> requestMap = Map.of(
                    "id", sku,
                    "link", aliExpressLink);
            String jsonRequest = objectMapper.writeValueAsString(requestMap);

            logger.info("Making API request to: {}", API_URL);
            logger.debug("Request body: {}", jsonRequest);

            RequestBody body = RequestBody.create(jsonRequest, JSON);
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(API_URL)
                    .post(body)
                    .build();

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body().string();
                logger.info("Response status: {}", response.code());
                logger.debug("Response body: {}", responseBody);

                if (!response.isSuccessful()) {
                    logger.error("API request failed with status: {}", response.code());
                    logger.error("Error response: {}", responseBody);
                    throw new IOException("Unexpected response " + response);
                }

                return objectMapper.readValue(responseBody, ProductResponse.class);
            }
        } catch (IOException e) {
            logger.error("Error making API request: {}", e.getMessage(), e);
            return null;
        }
    }

    public void processProductLinks(List<ProductLinks> productLinks) {
        int failedProducts = 0;
        int maxFailuresAllowed = productLinks.size() / 3; // Allow up to 1/3 of products to fail

        for (ProductLinks link : productLinks) {
            boolean processed = false;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    logger.info("Processing: {} (Attempt {} of 3)", link.getDropiLink(), attempt);
                    processProduct(link);
                    processed = true;
                    break;
                } catch (TimeoutError e) {
                    logger.error("Timeout on attempt {} while processing link: {} - {}", 
                        attempt, link.getDropiLink(), e.getMessage());
                    if (attempt < 3) {
                        logger.info("Waiting 30 seconds before retry...");
                        page.waitForTimeout(30000);
                    }
                } catch (Exception e) {
                    logger.error("Error on attempt {} while processing link: {} - {}", 
                        attempt, link.getDropiLink(), e.getMessage());
                    if (attempt < 3) {
                        logger.info("Waiting 30 seconds before retry...");
                        page.waitForTimeout(30000);
                    }
                }
            }

            if (!processed) {
                failedProducts++;
                logger.error("Failed to process product after 3 attempts: {}", link.getDropiLink());
                
                if (failedProducts > maxFailuresAllowed) {
                    logger.error("Too many failures ({}). Stopping processing.", failedProducts);
                    throw new RuntimeException("Too many product processing failures");
                }
            }

            // Add a small delay between products regardless of success/failure
            page.waitForTimeout(5000);
        }

        if (failedProducts > 0) {
            logger.warn("Completed with {} failed products out of {}", failedProducts, productLinks.size());
        } else {
            logger.info("Successfully processed all {} products", productLinks.size());
        }
    }

    private void processProduct(ProductLinks link) {
        page.navigate(link.getDropiLink());

        ElementHandle pricesTab = page.waitForSelector(
                "a#pills-prices-tab[data-toggle='pill'][data-target='#precos']",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(DEFAULT_TIMEOUT));

        if (pricesTab != null) {
            pricesTab.click();

            page.waitForSelector("tr.quantidade-variacoes",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(DEFAULT_TIMEOUT));

            page.waitForTimeout(5000);

            List<ElementHandle> variationRows = page.querySelectorAll("tr.quantidade-variacoes");

            for (ElementHandle row : variationRows) {
                ElementHandle skuInput = row.querySelector("input.sku-inputs-verify");
                if (skuInput != null) {
                    String sku = skuInput.getAttribute("value");
                    link.setSku(sku);
                    logger.info("Found SKU: {}", sku);

                    String rowId = skuInput.getAttribute("id").replace("sku-custom-", "");
                    ProductResponse response = findProduct(sku, link.getAliExpressLink());

                    if (response != null) {
                        double price = response.getPrice() / 100.0;
                        String formattedPrice = String.format("%.2f", price);
                        
                        // Set original price in the hidden input
                        ElementHandle originalPriceInput = page.querySelector("#preco-original-modificado-" + rowId);
                        if (originalPriceInput != null) {
                            originalPriceInput.fill(formattedPrice);
                            logger.debug("Set original price to: {} in input id: {}", formattedPrice, "preco-original-modificado-" + rowId);
                        } else {
                            logger.error("Could not find original price input for row: {}", rowId);
                        }

                        ElementHandle profitButton = row.querySelector("#lucro-" + rowId);
                        if (profitButton != null) {
                            profitButton.click();
                            logger.info("Clicked profit calculation button for SKU: {}", sku);

                            page.waitForTimeout(5000);
                            ElementHandle priceInput = page.querySelector("input.valor-produto-aliexpress");
                            ElementHandle marketingInput = page
                                    .querySelector("input.porcentagem-marketing");
                            ElementHandle markupInput = page.querySelector("input.base-markup");
                            ElementHandle promoMarkupInput = page
                                    .querySelector("input.base-markup-promocional");

                            if (priceInput != null && 
                                    marketingInput != null && markupInput != null &&
                                    promoMarkupInput != null) {

                                priceInput.fill(formattedPrice);
                                logger.info("Updated price to: {}", formattedPrice);

                                applyPriceRules(priceInput, marketingInput,
                                        markupInput, promoMarkupInput, price);

                                page.waitForTimeout(3000);

                                ElementHandle applyButton = page
                                        .querySelector("button#aplicarPrecosCalculadora");
                                if (applyButton != null) {
                                    applyButton.click();
                                    logger.info("Clicked apply button to save calculations");

                                    page.waitForTimeout(3000);
                                } else {
                                    logger.error("Could not find apply button");
                                }
                            }
                        }
                    } else {
                        logger.error("Could not price for product: {}", sku);
                    }
                }
            }
        }

        ElementHandle mainSaveButton = page.waitForSelector(
                "button.dropi--btn-primary[data-toggle='modal'][data-target='#atualizarProdutoModal']",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(DEFAULT_TIMEOUT));

        if (mainSaveButton != null) {
            mainSaveButton.click();
            logger.info("Clicked main save button");

            page.waitForTimeout(8000);

            ElementHandle finalSaveButton = page.waitForSelector(
                    "button.salvarProduto",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(DEFAULT_TIMEOUT));

            if (finalSaveButton != null) {
                Page.WaitForURLOptions waitOptions = new Page.WaitForURLOptions()
                        .setTimeout(DEFAULT_TIMEOUT * 2);

                finalSaveButton.click();
                logger.info("Clicked final save button");

                page.waitForURL("**/produtos", waitOptions);
                logger.info("Navigation completed after save");

                page.waitForLoadState();
                page.waitForTimeout(10000);
            } else {
                logger.error("Could not find final save button");
            }
        } else {
            logger.error("Could not find main save button");
        }
    }

    private void applyPriceRules(ElementHandle priceInput, ElementHandle marketingInput,
            ElementHandle markupInput, ElementHandle promoMarkupInput, double price) {
        logger.debug("Applying price rules for price: R$ {}", price);

        String marketingPercent;
        String markupPercent;
        String promoMarkupPercent;
        marketingPercent = "2,00";

        if (price <= 100.0) {
            logger.debug("Applying rules for price range: R$ 1,00 - R$ 100,00");
            markupPercent = "25,00";
            promoMarkupPercent = "20,00";
        } else if (price <= 200.0) {
            logger.debug("Applying rules for price range: R$ 101,00 - R$ 200,00");
            markupPercent = "25,00";
            promoMarkupPercent = "15,00";
        } else if (price <= 300.0) {
            logger.debug("Applying rules for price range: R$ 201,00 - R$ 300,00");
            markupPercent = "20,00";
            promoMarkupPercent = "15,00";
        } else if (price <= 400.0) {
            logger.debug("Applying rules for price range: R$ 301,00 - R$ 400,00");
            markupPercent = "15,00";
            promoMarkupPercent = "10,00";
        } else if (price <= 500.0) {
            logger.debug("Applying rules for price range: R$ 401,00 - R$ 500,00");
            markupPercent = "20,00";
            promoMarkupPercent = "10,00";
        } else {
            logger.debug("Applying rules for price range: > R$ 501,00");
            markupPercent = "15,00";
            promoMarkupPercent = "10,00";
        }

        marketingInput.fill(marketingPercent);
        logger.info("Updated marketing percentage to: {}", marketingPercent);

        markupInput.fill(markupPercent);
        logger.info("Updated markup multiplier to: {}", markupPercent);

        promoMarkupInput.fill(promoMarkupPercent);
        logger.info("Updated promotional markup to: {}", promoMarkupPercent);
    }

    @Override
    public void close() {
        logger.info("Closing PlaywrightService resources");
        try {
            if (page != null) {
                page.close();
            }
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
            logger.info("Resources closed successfully");
        } catch (Exception e) {
            logger.error("Error while closing resources", e);
            throw e;
        }
    }
}