package com.shop.model;


public class CashLedger {


    private int id;

    private String type;

    private double amount;

    private String dateTime;

    private int relatedTransactionId;

    private String notes;

    private String personName;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getDateTime() {
        return dateTime;
    }

    public void setDateTime(String dateTime) {
        this.dateTime = dateTime;
    }

    public int getRelatedTransactionId() {
        return relatedTransactionId;
    }

    public void setRelatedTransactionId(int relatedTransactionId) {
        this.relatedTransactionId = relatedTransactionId;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getPersonName() {
        return personName;
    }

    public void setPersonName(String personName) {
        this.personName = personName;
    }

}