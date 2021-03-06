package com.github.kfcfans.powerjob.worker.actors;

import akka.actor.AbstractActor;
import com.github.kfcfans.powerjob.common.model.InstanceDetail;
import com.github.kfcfans.powerjob.common.request.ServerQueryInstanceStatusReq;
import com.github.kfcfans.powerjob.common.request.ServerScheduleJobReq;
import com.github.kfcfans.powerjob.common.request.ServerStopInstanceReq;
import com.github.kfcfans.powerjob.worker.core.tracker.task.TaskTracker;
import com.github.kfcfans.powerjob.worker.core.tracker.task.TaskTrackerPool;
import com.github.kfcfans.powerjob.worker.persistence.TaskDO;
import com.github.kfcfans.powerjob.worker.pojo.request.BroadcastTaskPreExecuteFinishedReq;
import com.github.kfcfans.powerjob.worker.pojo.request.ProcessorMapTaskRequest;
import com.github.kfcfans.powerjob.worker.pojo.request.ProcessorReportTaskStatusReq;
import com.github.kfcfans.powerjob.common.response.AskResponse;
import com.github.kfcfans.powerjob.worker.pojo.request.ProcessorTrackerStatusReportReq;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * worker的master节点，处理来自server的jobInstance请求和来自worker的task请求
 *
 * @author tjq
 * @since 2020/3/17
 */
@Slf4j
public class TaskTrackerActor extends AbstractActor {

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(ProcessorReportTaskStatusReq.class, this::onReceiveProcessorReportTaskStatusReq)
                .match(ServerScheduleJobReq.class, this::onReceiveServerScheduleJobReq)
                .match(ProcessorMapTaskRequest.class, this::onReceiveProcessorMapTaskRequest)
                .match(ProcessorTrackerStatusReportReq.class, this::onReceiveProcessorTrackerStatusReportReq)
                .match(BroadcastTaskPreExecuteFinishedReq.class, this::onReceiveBroadcastTaskPreExecuteFinishedReq)
                .match(ServerStopInstanceReq.class, this::onReceiveServerStopInstanceReq)
                .match(ServerQueryInstanceStatusReq.class, this::onReceiveServerQueryInstanceStatusReq)
                .matchAny(obj -> log.warn("[ServerRequestActor] receive unknown request: {}.", obj))
                .build();
    }


    /**
     * 子任务状态上报 处理器
     */
    private void onReceiveProcessorReportTaskStatusReq(ProcessorReportTaskStatusReq req) {

        TaskTracker taskTracker = TaskTrackerPool.getTaskTrackerPool(req.getInstanceId());
        // 手动停止 TaskTracker 的情况下会出现这种情况
        if (taskTracker == null) {
            log.warn("[TaskTrackerActor] receive ProcessorReportTaskStatusReq({}) but system can't find TaskTracker.", req);
        } else {
            taskTracker.updateTaskStatus(req.getTaskId(), req.getStatus(), req.getReportTime(), req.getResult());
        }
    }

    /**
     * 子任务 map 处理器
     */
    private void onReceiveProcessorMapTaskRequest(ProcessorMapTaskRequest req) {

        TaskTracker taskTracker = TaskTrackerPool.getTaskTrackerPool(req.getInstanceId());
        if (taskTracker == null) {
            log.warn("[TaskTrackerActor] receive ProcessorMapTaskRequest({}) but system can't find TaskTracker.", req);
            return;
        }

        boolean success = false;
        List<TaskDO> subTaskList = Lists.newLinkedList();

        try {

            req.getSubTasks().forEach(originSubTask -> {
                TaskDO subTask = new TaskDO();

                subTask.setTaskName(req.getTaskName());
                subTask.setSubInstanceId(req.getSubInstanceId());

                subTask.setTaskId(originSubTask.getTaskId());
                subTask.setTaskContent(originSubTask.getTaskContent());

                subTaskList.add(subTask);
            });

            success = taskTracker.submitTask(subTaskList);
        }catch (Exception e) {
            log.warn("[TaskTrackerActor] process map task(instanceId={}) failed.", req.getInstanceId(), e);
        }

        AskResponse response = new AskResponse();
        response.setSuccess(success);
        getSender().tell(response, getSelf());
    }

    /**
     * 广播任务前置任务执行完毕 处理器
     */
    private void onReceiveBroadcastTaskPreExecuteFinishedReq(BroadcastTaskPreExecuteFinishedReq req) {

        TaskTracker taskTracker = TaskTrackerPool.getTaskTrackerPool(req.getInstanceId());
        if (taskTracker == null) {
            log.warn("[TaskTrackerActor] receive BroadcastTaskPreExecuteFinishedReq({}) but system can't find TaskTracker.", req);
            return;
        }
        taskTracker.broadcast(req.isSuccess(), req.getSubInstanceId(), req.getTaskId(), req.getReportTime(), req.getMsg());
    }

    /**
     * 服务器任务调度处理器
     */
    private void onReceiveServerScheduleJobReq(ServerScheduleJobReq req) {
        Long instanceId = req.getInstanceId();
        TaskTracker taskTracker = TaskTrackerPool.getTaskTrackerPool(instanceId);

        if (taskTracker != null) {
            log.warn("[TaskTrackerActor] TaskTracker({}) for instance(id={}) already exists.", taskTracker, instanceId);
            return;
        }

        log.debug("[TaskTrackerActor] server schedule job by request: {}.", req);
        // 原子创建，防止多实例的存在
        TaskTrackerPool.atomicCreateTaskTracker(instanceId, ignore -> TaskTracker.create(req));
    }

    /**
     * ProcessorTracker 心跳处理器
     */
    private void onReceiveProcessorTrackerStatusReportReq(ProcessorTrackerStatusReportReq req) {
        TaskTracker taskTracker = TaskTrackerPool.getTaskTrackerPool(req.getInstanceId());
        if (taskTracker == null) {
            log.warn("[TaskTrackerActor] receive ProcessorTrackerStatusReportReq({}) but system can't find TaskTracker.", req);
            return;
        }
        taskTracker.receiveProcessorTrackerHeartbeat(req);
    }

    /**
     * 停止任务实例
     */
    private void onReceiveServerStopInstanceReq(ServerStopInstanceReq req) {
        TaskTracker taskTracker = TaskTrackerPool.getTaskTrackerPool(req.getInstanceId());
        if (taskTracker == null) {
            log.warn("[TaskTrackerActor] receive ServerStopInstanceReq({}) but system can't find TaskTracker.", req);
            return;
        }
        taskTracker.destroy();
    }

    /**
     * 查询任务实例运行状态
     */
    private void onReceiveServerQueryInstanceStatusReq(ServerQueryInstanceStatusReq req) {
        AskResponse askResponse;
        TaskTracker taskTracker = TaskTrackerPool.getTaskTrackerPool(req.getInstanceId());
        if (taskTracker == null) {
            log.warn("[TaskTrackerActor] receive ServerQueryInstanceStatusReq({}) but system can't find TaskTracker.", req);
            askResponse = AskResponse.failed("can't find TaskTracker");
        }else {
            InstanceDetail instanceDetail = taskTracker.fetchRunningStatus();
            askResponse = AskResponse.succeed(instanceDetail);
        }
        getSender().tell(askResponse, getSelf());
    }
}
