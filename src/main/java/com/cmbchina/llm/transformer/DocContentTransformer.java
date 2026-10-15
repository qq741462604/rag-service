package com.cmbchina.llm.transformer;

import com.cmbchina.llm.entity.model.DocContentDTO;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DocContentTransformer {

    private static final double REVIEW_THRESHOLD = 0.95;

    public static void applyReviewRule(DocContentDTO.FieldDTO field) {
        if (field.getConfidence() != null
                && field.getConfidence() < REVIEW_THRESHOLD) {
            field.setNeedsReview(true);
            field.setRemarks("置信度低于 " + REVIEW_THRESHOLD + "，需人工确认");
        }
    }
}
