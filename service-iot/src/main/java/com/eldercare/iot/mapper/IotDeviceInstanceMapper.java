package com.eldercare.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.heartbeat.HeartbeatState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface IotDeviceInstanceMapper extends BaseMapper<IotDeviceInstance> {

    @Update({
            "<script>",
            "UPDATE iot_device_instance AS d",
            "SET online_status = v.online_status,",
            "    last_heartbeat_at = v.last_heartbeat_at,",
            "    updated_at = CURRENT_TIMESTAMP",
            "FROM (VALUES",
            "<foreach collection='snapshots' item='snapshot' separator=','>",
            "(#{snapshot.deviceId}, #{snapshot.onlineStatus.code}, #{snapshot.lastHeartbeatAt})",
            "</foreach>",
            ") AS v(device_id, online_status, last_heartbeat_at)",
            "WHERE d.device_id = v.device_id",
            "  AND (d.online_status IS DISTINCT FROM v.online_status",
            "       OR d.last_heartbeat_at IS NULL",
            "       OR d.last_heartbeat_at &lt; v.last_heartbeat_at)",
            "</script>"
    })
    int updateHeartbeatSnapshots(@Param("snapshots") List<HeartbeatState> snapshots);
}
