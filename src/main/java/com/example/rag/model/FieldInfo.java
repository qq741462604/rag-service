package com.example.rag.model;

public class FieldInfo {
    public String canonicalField;
    public String columnName;
    public String dataType;
    public String length;
    public String description;
    public String aliases;
    public String remark;
    public int priorityLevel;
    public float[] embedding; // may be null

    public FieldInfo() {}
}
