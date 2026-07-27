package com.eldercare.iot.parser;

import com.eldercare.iot.parser.model.ParsedEvent;
import com.eldercare.iot.parser.model.RawDeviceMessage;
import java.util.Optional;

public interface DeviceMessageParser {
    String parserCode();
    Optional<ParsedEvent> parse(RawDeviceMessage raw);

    /**
     * 校验协议版本是否受当前解析器支持。
     * 模拟器协议默认仅支持 1.0。
     */
    default boolean supportsProtocolVersion(String protocolVersion) {
        return "1.0".equals(protocolVersion);
    }
}
