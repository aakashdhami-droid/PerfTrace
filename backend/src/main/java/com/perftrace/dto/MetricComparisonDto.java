package com.perftrace.dto;

public class MetricComparisonDto {

    private String name;
    private String category;
    private String unit;
    private Double valueA;
    private Double valueB;
    private Double difference;
    private Double percentChange;
    private String interpretation;

    public MetricComparisonDto() {}

    public MetricComparisonDto(String name, String category, String unit,
                               Double valueA, Double valueB, String interpretation) {
        this.name = name;
        this.category = category;
        this.unit = unit;
        this.valueA = valueA;
        this.valueB = valueB;
        if (valueA != null && valueB != null) {
            this.difference = Math.round((valueB - valueA) * 100.0) / 100.0;
            if (valueA != 0.0) {
                this.percentChange = Math.round(((valueB - valueA) / valueA * 100.0) * 10.0) / 10.0;
            }
        }
        this.interpretation = interpretation;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public Double getValueA() { return valueA; }
    public void setValueA(Double valueA) { this.valueA = valueA; }

    public Double getValueB() { return valueB; }
    public void setValueB(Double valueB) { this.valueB = valueB; }

    public Double getDifference() { return difference; }
    public void setDifference(Double difference) { this.difference = difference; }

    public Double getPercentChange() { return percentChange; }
    public void setPercentChange(Double percentChange) { this.percentChange = percentChange; }

    public String getInterpretation() { return interpretation; }
    public void setInterpretation(String interpretation) { this.interpretation = interpretation; }
}
