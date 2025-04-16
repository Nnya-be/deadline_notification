package com.controller;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Common implements RequestHandler<Object, Object> {
    private static final Logger log = LoggerFactory.getLogger(Common.class);

    @Override
    public Object handleRequest(Object o, Context context) {
        log.info("Hello World");
        return null;
    }
}
