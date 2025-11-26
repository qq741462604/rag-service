package com.example.rag.model;

public class VectorNode {
    private String id;
    private String content;
    private float[] vector;
    private String source;

    public VectorNode(String id, String content, float[] vector, String source) {
        this.id = id;
        this.content = content;
        this.vector = vector;
        this.source = source;
    }
    public String getId() { return id; }
    public String getContent() { return content; }
    public float[] getVector() { return vector; }
    public String getSource() { return source; }
}
