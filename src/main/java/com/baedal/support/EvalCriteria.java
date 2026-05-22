package com.baedal.support;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record EvalCriteria(
        Set<String> allowedNextActions,
        List<String> violationKeywords,
        Map<String, String> neededInfoRequirements
) {}
