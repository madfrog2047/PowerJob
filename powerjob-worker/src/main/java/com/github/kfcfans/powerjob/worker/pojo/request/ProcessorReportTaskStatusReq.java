package com.github.kfcfans.powerjob.worker.pojo.request;

import com.github.kfcfans.powerjob.common.OmsSerializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * worker 上报 task 执行情况
 *
 * @author tjq
 * @since 2020/3/17
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessorReportTaskStatusReq implements OmsSerializable {

    private Long instanceId;
    private String taskId;

    private int status;
    /**
     * 执行完成时才有
     */
    private String result;

    // 上报时间
    private long reportTime;

}
