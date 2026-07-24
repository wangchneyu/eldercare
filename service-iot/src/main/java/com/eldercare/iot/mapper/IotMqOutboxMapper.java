package com.eldercare.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eldercare.iot.entity.IotMqOutbox;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IotMqOutboxMapper extends BaseMapper<IotMqOutbox> {
}
