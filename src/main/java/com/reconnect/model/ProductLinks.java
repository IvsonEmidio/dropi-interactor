package com.reconnect.model;

public class ProductLinks {
    private final String dropiLink;
    private final String aliExpressLink;
    private String sku;

    public ProductLinks(String dropiLink, String aliExpressLink) {
        this.dropiLink = dropiLink;
        this.aliExpressLink = aliExpressLink;
    }

    public String getDropiLink() {
        return dropiLink;
    }

    public String getAliExpressLink() {
        return aliExpressLink;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    @Override
    public String toString() {
        return "ProductLinks{" +
                "dropiLink='" + dropiLink + '\'' +
                ", aliExpressLink='" + aliExpressLink + '\'' +
                ", sku='" + sku + '\'' +
                '}';
    }
} 