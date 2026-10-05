package com.shop.model;


public class Product {


    private int id;

    private String code;

    private int companyId;

    private int categoryId;

    private String name;

    private String size;

    private String unitType;

    private double defaultWholesalePrice;

    private double defaultSellingPrice;

    private String notes;

    private boolean active;



    public Product(){

    }



    public int getId() {
        return id;
    }


    public void setId(int id) {
        this.id = id;
    }



    public String getCode() {
        return code;
    }


    public void setCode(String code) {
        this.code = code;
    }



    public int getCompanyId() {
        return companyId;
    }


    public void setCompanyId(int companyId) {
        this.companyId = companyId;
    }



    public int getCategoryId() {
        return categoryId;
    }


    public void setCategoryId(int categoryId) {
        this.categoryId = categoryId;
    }



    public String getName() {
        return name;
    }


    public void setName(String name) {
        this.name = name;
    }



    public String getSize() {
        return size;
    }


    public void setSize(String size) {
        this.size = size;
    }



    public String getUnitType() {
        return unitType;
    }


    public void setUnitType(String unitType) {
        this.unitType = unitType;
    }



    public double getDefaultWholesalePrice() {
        return defaultWholesalePrice;
    }


    public void setDefaultWholesalePrice(double price) {
        this.defaultWholesalePrice = price;
    }



    public double getDefaultSellingPrice() {
        return defaultSellingPrice;
    }


    public void setDefaultSellingPrice(double price) {
        this.defaultSellingPrice = price;
    }



    public String getNotes() {
        return notes;
    }


    public void setNotes(String notes) {
        this.notes = notes;
    }



    public boolean isActive() {
        return active;
    }


    public void setActive(boolean active) {
        this.active = active;
    }


}