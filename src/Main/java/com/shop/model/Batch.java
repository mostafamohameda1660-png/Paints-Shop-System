package com.shop.model;


public class Batch {


    private int id;

    private int productId;

    private double quantityRemaining;

    private double wholesalePrice;

    private String dateIn;



    public int getId() {
        return id;
    }


    public void setId(int id) {
        this.id = id;
    }


    public int getProductId() {
        return productId;
    }


    public void setProductId(int productId) {
        this.productId = productId;
    }


    public double getQuantityRemaining() {
        return quantityRemaining;
    }


    public void setQuantityRemaining(double quantityRemaining) {
        this.quantityRemaining = quantityRemaining;
    }


    public double getWholesalePrice() {
        return wholesalePrice;
    }


    public void setWholesalePrice(double wholesalePrice) {
        this.wholesalePrice = wholesalePrice;
    }


    public String getDateIn() {
        return dateIn;
    }


    public void setDateIn(String dateIn) {
        this.dateIn = dateIn;
    }


}