package com.eldercare.iot.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;

@Slf4j
@Component
public class ParserRegistry {
    private final Map<String, DeviceMessageParser> parsers = new HashMap<>();

    public ParserRegistry(List<DeviceMessageParser> parserList) {
        for (DeviceMessageParser parser : parserList) {
            parsers.put(parser.parserCode(), parser);
            log.info("注册协议解析器: {} -> {}", parser.parserCode(), parser.getClass().getSimpleName());
        }
    }

    public Optional<DeviceMessageParser> getParser(String parserCode) {
        return Optional.ofNullable(parsers.get(parserCode));
    }
}
