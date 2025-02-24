package com.reconnect;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.reconnect.service.PlaywrightService;
import com.reconnect.model.ProductLinks;
import java.util.List;

public class Main {
    private static final Logger logger = LogManager.getLogger(Main.class);
    
    public static void main(String[] args) {
        logger.info("Starting application");
        try (PlaywrightService playwrightService = new PlaywrightService()) {
            logger.info("Navigating to products page");
            playwrightService.navigateTo("https://app.dropi.com.br/produtos");
            
            logger.debug("Waiting for page load");
            Thread.sleep(5000);
            
            logger.info("Extracting product links");
            List<ProductLinks> productLinks = playwrightService.extractProductLinks();
            logger.debug("Found {} products to process", productLinks.size());
            
            logger.info("Processing product links");
            playwrightService.processProductLinks(productLinks);
            
            logger.info("Application completed successfully");
        } catch (InterruptedException e) {
            logger.error("Application interrupted", e);
            System.exit(1);
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            System.exit(1);
        }
    }
}