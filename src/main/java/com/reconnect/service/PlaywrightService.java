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
    private static final int DEFAULT_TIMEOUT = 30000; // 30 seconds
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
        
        try {
            logger.info("Starting product links extraction");
            // Wait for the table rows to be visible and ensure at least one exists
            page.waitForSelector("tr.dropi--table-row-product", 
                new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(DEFAULT_TIMEOUT));
            
            // Wait a bit more to ensure all rows are loaded
            page.waitForTimeout(2000);
            
            // Get all product rows
            List<ElementHandle> rows = page.querySelectorAll("tr.dropi--table-row-product");
            
            logger.debug("Found {} product rows", rows.size());
            
            for (ElementHandle row : rows) {
                try {
                    // Check if row has the "Anúncio removido" tag
                    ElementHandle removedTag = row.querySelector(
                        "span.dropi--tag-red[data-original-title='O anúncio deste produto no Fornecedor foi removido, altere o produto para não exibir em sua loja']"
                    );
                    
                    if (removedTag != null) {
                        logger.debug("Skipping removed product");
                        continue; // Skip this row
                    }
                    
                    // Extract Dropi edit link with timeout
                    ElementHandle dropiLink = row.waitForSelector(
                        "a[href^='https://app.dropi.com.br/editar/produto/']",
                        new ElementHandle.WaitForSelectorOptions().setTimeout(5000)
                    );
                    
                    // Extract AliExpress link with timeout
                    ElementHandle aliExpressLink = row.waitForSelector(
                        "a[href^='https://pt.aliexpress.com/item/']",
                        new ElementHandle.WaitForSelectorOptions().setTimeout(5000)
                    );
                    
                    if (dropiLink != null && aliExpressLink != null) {
                        productLinks.add(new ProductLinks(
                            dropiLink.getAttribute("href"),
                            aliExpressLink.getAttribute("href")
                        ));
                    }
                } catch (TimeoutError e) {
                    logger.error("Timeout while processing row: {}", e.getMessage());
                    continue; // Skip this row and continue with the next
                }
            }
        } catch (TimeoutError e) {
            logger.error("Failed to extract product links", e);
            throw e; // Re-throw the error as this is a critical failure
        }
        
        logger.info("Completed extracting {} product links", productLinks.size());
        return productLinks;
    }

    private ProductResponse findProduct(String sku, String aliExpressLink) {
        try {
            Map<String, String> requestMap = Map.of(
                "id", sku,
                "link", aliExpressLink
            );
            String jsonRequest = objectMapper.writeValueAsString(requestMap);

            System.out.println("Making API request to: " + API_URL);
            System.out.println("Request body: " + jsonRequest);

            RequestBody body = RequestBody.create(jsonRequest, JSON);
            okhttp3.Request request = new okhttp3.Request.Builder()
                .url(API_URL)
                .post(body)
                .build();

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body().string();
                System.out.println("Response status: " + response.code());
                System.out.println("Response body: " + responseBody);

                if (!response.isSuccessful()) {
                    System.err.println("API request failed with status: " + response.code());
                    System.err.println("Error response: " + responseBody);
                    throw new IOException("Unexpected response " + response);
                }
                
                return objectMapper.readValue(responseBody, ProductResponse.class);
            }
        } catch (IOException e) {
            System.err.println("Error making API request: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public void processProductLinks(List<ProductLinks> productLinks) {
        for (ProductLinks link : productLinks) {
            try {
                System.out.println("Processing: " + link.getDropiLink());
                
                // Navigate to the Dropi product page
                page.navigate(link.getDropiLink());
                
                // Wait for the prices tab to be visible and clickable
                ElementHandle pricesTab = page.waitForSelector(
                    "a#pills-prices-tab[data-toggle='pill'][data-target='#precos']",
                    new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(DEFAULT_TIMEOUT)
                );
                
                if (pricesTab != null) {
                    // Click the prices tab
                    pricesTab.click();
                    
                    // Wait for the table to be visible
                    page.waitForSelector("tr.quantidade-variacoes", 
                        new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(DEFAULT_TIMEOUT));
                    
                    // Wait a bit for content to load
                    page.waitForTimeout(2000);
                    
                    // Get all variation rows
                    List<ElementHandle> variationRows = page.querySelectorAll("tr.quantidade-variacoes");
                    
                    for (ElementHandle row : variationRows) {
                        // Find and get SKU value
                        ElementHandle skuInput = row.querySelector("input.sku-inputs-verify");
                        if (skuInput != null) {
                            String sku = skuInput.getAttribute("value");
                            link.setSku(sku);
                            System.out.println("Found SKU: " + sku);
                            
                            // Get the row ID from the SKU input id
                            String rowId = skuInput.getAttribute("id").replace("sku-custom-", "");
                            
                            // Find and click the profit calculation button
                            ElementHandle profitButton = row.querySelector("#lucro-" + rowId);
                            if (profitButton != null) {
                                profitButton.click();
                                System.out.println("Clicked profit calculation button for SKU: " + sku);
                                
                                // Wait for modal to appear
                                page.waitForTimeout(2000);
                                
                                // Make API request
                                ProductResponse response = findProduct(sku, link.getAliExpressLink());
                                if (response != null) {
                                    // Find and update all inputs
                                    ElementHandle priceInput = page.querySelector("input.valor-produto-aliexpress");
                                    ElementHandle shippingInput = page.querySelector("input.valor-frete-aliexpress");
                                    ElementHandle marketingInput = page.querySelector("input.porcentagem-marketing");
                                    ElementHandle markupInput = page.querySelector("input.base-markup");
                                    ElementHandle promoMarkupInput = page.querySelector("input.base-markup-promocional");
                                    
                                    if (priceInput != null && shippingInput != null && 
                                        marketingInput != null && markupInput != null &&
                                        promoMarkupInput != null) {
                                        
                                        // Format price to string with 2 decimal places
                                        double price = response.getPrice() / 100.0;
                                        String formattedPrice = String.format("%.2f", price);
                                        priceInput.fill(formattedPrice);
                                        logger.info("Updated price to: {}", formattedPrice);
                                        
                                        // Apply price rules based on price range
                                        applyPriceRules(priceInput, shippingInput, marketingInput, 
                                                      markupInput, promoMarkupInput, price);
                                        
                                        // Wait a bit for calculations to update
                                        page.waitForTimeout(1000);
                                        
                                        // Find and click the apply button
                                        ElementHandle applyButton = page.querySelector("button#aplicarPrecosCalculadora");
                                        if (applyButton != null) {
                                            applyButton.click();
                                            System.out.println("Clicked apply button to save calculations");
                                            
                                            // Wait for modal to close
                                            page.waitForTimeout(1000);
                                        } else {
                                            System.err.println("Could not find apply button");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // After processing all variations, click the main save button
                ElementHandle mainSaveButton = page.waitForSelector(
                    "button.dropi--btn-primary[data-toggle='modal'][data-target='#atualizarProdutoModal']",
                    new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(DEFAULT_TIMEOUT)
                );
                
                if (mainSaveButton != null) {
                    mainSaveButton.click();
                    System.out.println("Clicked main save button");
                    
                    // Increased wait for the modal to appear
                    page.waitForTimeout(3000);
                    
                    // Find and check the ignore cost checkbox by clicking its label text
                    ElementHandle ignoreCostLabel = page.waitForSelector(
                        "p.ml-4:text('Ignorar atualização do custo das variações do produto.')",
                        new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(DEFAULT_TIMEOUT)
                    );
                    
                    if (ignoreCostLabel != null) {
                        ignoreCostLabel.click();
                        System.out.println("Clicked ignore cost checkbox label");
                        
                        // Added delay after clicking checkbox
                        page.waitForTimeout(2000);
                        
                        // Find and click the final save button
                        ElementHandle finalSaveButton = page.waitForSelector(
                            "button.salvarProduto",
                            new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(DEFAULT_TIMEOUT)
                        );
                        
                        if (finalSaveButton != null) {
                            // Create a Promise for navigation before clicking
                            Page.WaitForURLOptions waitOptions = new Page.WaitForURLOptions()
                                .setTimeout(DEFAULT_TIMEOUT * 2); // Doubled timeout for navigation
                            
                            // Start waiting for navigation to products page
                            finalSaveButton.click();
                            System.out.println("Clicked final save button");
                            
                            // Wait for navigation to complete
                            page.waitForURL("**/produtos", waitOptions);
                            System.out.println("Navigation completed after save");
                            
                            // Increased wait to ensure page is fully loaded
                            page.waitForLoadState();
                            page.waitForTimeout(5000);
                        } else {
                            System.err.println("Could not find final save button");
                        }
                    } else {
                        System.err.println("Could not find ignore cost checkbox label");
                    }
                } else {
                    System.err.println("Could not find main save button");
                }
                
            } catch (TimeoutError e) {
                System.err.println("Timeout while processing link: " + link.getDropiLink());
                System.err.println("Error: " + e.getMessage());
                continue;
            }
        }
    }

    private void applyPriceRules(ElementHandle priceInput, ElementHandle shippingInput, 
                                ElementHandle marketingInput, ElementHandle markupInput, 
                                ElementHandle promoMarkupInput, double price) {
        logger.debug("Applying price rules for price: R$ {}", price);
        
        // Format price inputs with Brazilian number format (comma as decimal separator)
        String shippingPrice;
        String marketingPercent;
        String markupPercent;
        String promoMarkupPercent;
        
        if (price <= 99.0) {
            logger.debug("Applying rules for price range: R$ 1,00 - R$ 99,00");
            shippingPrice = "24,00";
            marketingPercent = "10,00";
            markupPercent = "20,00";
            promoMarkupPercent = "15,00";
        } else if (price <= 250.0) {
            logger.debug("Applying rules for price range: R$ 100,00 - R$ 250,00");
            shippingPrice = "00,00";
            marketingPercent = "10,00";
            markupPercent = "15,00";
            promoMarkupPercent = "10,00";
        } else if (price <= 500.0) {
            logger.debug("Applying rules for price range: R$ 251,00 - R$ 500,00");
            shippingPrice = "00,00";
            marketingPercent = "10,00";
            markupPercent = "10,00";
            promoMarkupPercent = "7,00";
        } else {
            logger.debug("Applying rules for price range: > R$ 501,00");
            shippingPrice = "00,00";
            marketingPercent = "7,00";
            markupPercent = "10,00";
            promoMarkupPercent = "7,00";
        }
        
        // Apply the values to inputs
        shippingInput.fill(shippingPrice);
        logger.info("Updated shipping price to: {}", shippingPrice);
        
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