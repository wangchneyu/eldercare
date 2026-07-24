package com.eldercare.iot.parser;

import com.eldercare.iot.parser.model.ParsedEvent;
import com.eldercare.iot.parser.model.RawDeviceMessage;
import java.util.Optional;

public interface DeviceMessageParser {
    String parserCode();
    Optional<ParsedEvent> parse(RawDeviceMessage raw);
}
