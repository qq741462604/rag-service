package com.example.rag.model;

public class RagResult {
    private String canonicalField;
    private double confidence;
    private String source;
    private String matchedAlias;
    private String description;
    private String status; // EX: EXACT, STRING_SIMILAR, SEMANTIC, NOT_FOUND

    public RagResult() {}

    public String getCanonicalField() { return canonicalField; }
    public void setCanonicalField(String canonicalField) { this.canonicalField = canonicalField; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getMatchedAlias() { return matchedAlias; }
    public void setMatchedAlias(String matchedAlias) { this.matchedAlias = matchedAlias; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
