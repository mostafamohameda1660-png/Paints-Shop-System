package com.shop.model;


public class Category {


    private int id;

    private String name;
    private String code;
    private String units;
    private String measurementUnits;


    public Category(){

    }


    public Category(int id, String name){

        this.id = id;
        this.name = name;

    }

    public Category(int id, String name, String code, String units, String measurementUnits){
        this.id = id;
        this.name = name;
        this.code = code;
        this.units = units;
        this.measurementUnits = measurementUnits;
    }


    public int getId() {
        return id;
    }


    public void setId(int id) {
        this.id = id;
    }


    public String getName() {
        return name;
    }


    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getUnits() {
        return units;
    }

    public void setUnits(String units) {
        this.units = units;
    }

    public String getMeasurementUnits() {
        return measurementUnits;
    }

    public void setMeasurementUnits(String measurementUnits) {
        this.measurementUnits = measurementUnits;
    }

}