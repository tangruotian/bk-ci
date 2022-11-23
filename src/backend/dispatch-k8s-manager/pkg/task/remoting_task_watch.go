package task

import (
	"disaptch-k8s-manager/pkg/kubeclient"
	"disaptch-k8s-manager/pkg/logs"
	"disaptch-k8s-manager/pkg/remoting"
	"disaptch-k8s-manager/pkg/types"
	"fmt"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/watch"
)

func WatchRemotingTaskDeployment() {
	watchStopChan := make(chan bool)
	for {
		watcher, err := kubeclient.WatchDeployment(remoting.RemotingWatchLabel)
		if err != nil {
			logs.Error("WatchRemotingTaskDeployment WatchDeployment error ", err)
			continue
		}

		go watchRemotingTaskDeployment(watcher, watchStopChan)

		<-watchStopChan
		logs.Warn("watchTaskDeployment chan reWake. ")
	}
}

func watchRemotingTaskDeployment(watcher watch.Interface, watchStopChan chan<- bool) {

	defer watcher.Stop()

loop:
	for {
		event, ok := <-watcher.ResultChan()
		if !ok {
			logs.Warn("watchTaskDeployment chan close. ")
			watchStopChan <- true
			break loop
		}

		dep, ok := event.Object.(*appsv1.Deployment)
		if !ok {
			continue
		}
		labelValue, ok := dep.Labels[remoting.RemotingWatchLabel]
		if !ok {
			continue
		}

		taskId, labelType, action, ok := parseTaskLabelValue(labelValue)
		if !ok {
			logs.Error(fmt.Sprintf("watch task label value %s format error. ", labelValue))
		}

		switch labelType {
		case types.RemotingWorkspaceTaskLabel:
			watchRemotingTaskDeploymentStop(event, dep, taskId, action)
		default:
			logs.Error(fmt.Sprintf("remoting watch task label labelType %s not support. ", labelType))
		}
	}
}

func watchRemotingTaskDeploymentStop(event watch.Event, dep *appsv1.Deployment, taskId string, action types.TaskAction) {
	if action == types.TaskActionStop {
		switch event.Type {
		case watch.Modified:
			if dep.Spec.Replicas != nil && *dep.Spec.Replicas == 0 {
				okTask(taskId)
			}
		case watch.Error:
			logs.Error("stop remoting error. ", dep)
			if len(dep.Status.Conditions) > 0 {
				failTask(taskId, dep.Status.Conditions[0].String())
			} else {
				failTask(taskId, "stop remoting error")
			}
		}
	}
}

func WatchRemotingTaskPod() {
	watchStopChan := make(chan bool)
	for {
		watcher, err := kubeclient.WatchPod(remoting.RemotingWatchLabel)
		if err != nil {
			logs.Error("WatchTaskPod WatchPod error", err)
			continue
		}

		go watchRemotingTaskPod(watcher, watchStopChan)

		<-watchStopChan
		logs.Warn("watchTaskPod chan reWake. ")
	}
}

func watchRemotingTaskPod(watcher watch.Interface, watchStopChan chan<- bool) {

	defer watcher.Stop()

loop:
	for {
		event, ok := <-watcher.ResultChan()
		if !ok {
			logs.Warn("watchTaskPod chan close. ")
			watchStopChan <- true
			break loop
		}

		pod, ok := event.Object.(*corev1.Pod)
		if !ok {
			continue
		}

		labelValue, ok := pod.Labels[remoting.RemotingWatchLabel]
		if !ok {
			logs.Warn(fmt.Sprintf("pod|%s no have task label", pod.Name))
			continue
		}

		taskId, labelType, action, ok := parseTaskLabelValue(labelValue)
		if !ok {
			logs.Error(fmt.Sprintf("watch task label value %s format error. ", labelValue))
			continue
		}

		switch labelType {
		case types.RemotingWorkspaceTaskLabel:
			watchRemotingTaskPodCreateOrStart(event, pod, taskId, action)
		default:
			logs.Error(fmt.Sprintf("remoting watch task label labelType %s not support. ", labelType))
		}
	}
}

func watchRemotingTaskPodCreateOrStart(event watch.Event, pod *corev1.Pod, taskId string, action types.TaskAction) {
	// 只观察start或者create相关，stop和stop不watch
	if action != types.TaskActionCreate && action != types.TaskActionStart {
		return
	}

	podStatus := pod.Status
	logs.Info(fmt.Sprintf("remoting|task|%s|pod|%s|statue|%s|type|%s", taskId, pod.Name, podStatus.Phase, event.Type))

	switch event.Type {
	case watch.Added, watch.Modified:
		{
			switch podStatus.Phase {
			case corev1.PodPending:
				updateTask(taskId, types.TaskRunning)
			// 对于task的start/create来说，启动了就算成功，而不关系启动成功还是失败了
			case corev1.PodRunning, corev1.PodSucceeded, corev1.PodFailed:
				okTask(taskId)
			case corev1.PodUnknown:
				updateTask(taskId, types.TaskUnknown)
			}
		}
	case watch.Error:
		{
			logs.Error("add remoting error. ", pod)
			failTask(taskId, podStatus.Message+"|"+podStatus.Reason)
		}
	}
}
