package com.perftrace.service.analysis;

import com.perftrace.entity.Classification;
import com.perftrace.entity.ThrottleEvidence;

public class DetectionResult {

    private final Classification classification;
    private final ThrottleEvidence throttleEvidence;
    private final String evidenceExplanation;

    public DetectionResult(Classification classification, ThrottleEvidence throttleEvidence, String evidenceExplanation) {
        this.classification = classification;
        this.throttleEvidence = throttleEvidence;
        this.evidenceExplanation = evidenceExplanation;
    }

    public Classification getClassification() { return classification; }
    public ThrottleEvidence getThrottleEvidence() { return throttleEvidence; }
    public String getEvidenceExplanation() { return evidenceExplanation; }
}
