package com.example.rag.service;


import com.example.rag.model.SearchResult;

public interface SearchEngine {

    /**
     * @param query 已加权后的 query
     */
    SearchResult search(String query);
}