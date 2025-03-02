package com.reconnect;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.reconnect.service.PlaywrightService;
import com.reconnect.model.ProductLinks;
import java.util.List;

public class Main {
    private static final Logger logger = LogManager.getLogger(Main.class);
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY = 30000; // 30 seconds
    
    public static void main(String[] args) {
        logger.info("Starting application");
        try (PlaywrightService playwrightService = new PlaywrightService()) {
            Thread.sleep(10000);
            
            List<ProductLinks> productLinks = null;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    logger.info("Attempt {} of {}: Extracting product links", attempt, MAX_RETRIES);
                    productLinks = playwrightService.extractProductLinks();
                    logger.debug("Found {} products to process", productLinks.size());
                    break;
                } catch (Exception e) {
                    logger.error("Failed to extract product links on attempt {}: {}", attempt, e.getMessage());
                    if (attempt == MAX_RETRIES) {
                        throw e;
                    }
                    logger.info("Waiting {} seconds before retry...", RETRY_DELAY / 1000);
                    Thread.sleep(RETRY_DELAY);
                }
            }
            
            if (productLinks != null && !productLinks.isEmpty()) {
                Thread.sleep(10000);
                
                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    try {
                        logger.info("Attempt {} of {}: Processing product links", attempt, MAX_RETRIES);
                        playwrightService.processProductLinks(productLinks);
                        break;
                    } catch (Exception e) {
                        logger.error("Failed to process product links on attempt {}: {}", attempt, e.getMessage());
                        if (attempt == MAX_RETRIES) {
                            throw e;
                        }
                        logger.info("Waiting {} seconds before retry...", RETRY_DELAY / 1000);
                        Thread.sleep(RETRY_DELAY);
                    }
                }
                
                logger.info("Application completed successfully");
            } else {
                logger.warn("No products found to process");
            }
        } catch (InterruptedException e) {
            logger.error("Application interrupted", e);
            System.exit(1);
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            System.exit(1);
        }
    }
}