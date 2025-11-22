package com.faas;

import java.util.Map;

public interface LocalLambdaFunction {

    String getName();

    Map<String, Object> handle (Map<String, Object> input);
}
