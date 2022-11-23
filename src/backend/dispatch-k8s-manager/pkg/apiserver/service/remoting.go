package service

import (
	"disaptch-k8s-manager/pkg/db/mysql"
	"disaptch-k8s-manager/pkg/kubeclient"
	"disaptch-k8s-manager/pkg/remoting"
	"disaptch-k8s-manager/pkg/task"
	"disaptch-k8s-manager/pkg/types"
	"fmt"
	"path/filepath"
	"strconv"
	"time"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
)

func CreateRemotingWorkspace(ws *RemotingWorkspace) (taskId string, err error) {
	volumes, volumeMounts := getWorkspaceVolumeAndMount(ws)

	var replicas int32 = 1

	taskId = generateTaskId()

	if err := mysql.InsertTask(types.Task{
		TaskId:     taskId,
		TaskKey:    ws.WorkspaceID,
		TaskBelong: types.TaskBelongRemotingWorkspace,
		Action:     types.TaskActionCreate,
		Status:     types.TaskWaiting,
		Message:    nil,
		ActionTime: time.Now(),
		UpdateTime: time.Now(),
	}); err != nil {
		return "", err
	}

	// TODO: 先定好4核8G
	requestCpu, err := resource.ParseQuantity("4")
	if err != nil {
		return "", err
	}
	requestMem, err := resource.ParseQuantity("8192Mi")
	if err != nil {
		return "", err
	}
	resources := &corev1.ResourceRequirements{
		Limits: map[corev1.ResourceName]resource.Quantity{
			corev1.ResourceCPU:    requestCpu,
			corev1.ResourceMemory: requestMem,
		},
		Requests: map[corev1.ResourceName]resource.Quantity{
			corev1.ResourceCPU:    requestCpu,
			corev1.ResourceMemory: requestMem,
		},
	}

	labels := getRemotingDispatchLabel(ws.WorkspaceID, taskId, types.TaskActionCreate, types.RemotingWorkspaceTaskLabel)

	// 创建remoting服务
	go task.DoCreateWorkspace(
		taskId,
		&kubeclient.Deployment{
			Name:   ws.WorkspaceID,
			Labels: labels,
			MatchLabels: map[string]string{
				remoting.RemotingCoreLabel: ws.WorkspaceID,
			},
			Replicas: &replicas,
			Pod: kubeclient.Pod{
				Labels:  labels,
				Volumes: volumes,
				Containers: []kubeclient.Container{
					{
						Image:        remoting.RemotingImage,
						Resources:    *resources,
						Env:          getRemotingEnvs(ws),
						VolumeMounts: volumeMounts,
						Ports: []corev1.ContainerPort{
							{ContainerPort: remoting.RemotingVscodeWebPort},
							{ContainerPort: remoting.RemotingApiPort},
							{ContainerPort: remoting.RemotingVscodeSSHPort},
						},
						WorkingDir: remoting.VolumeMountPath + "/" + ws.GitRepo.GitRepoName,
					},
				},
			},
		},
	)

	return taskId, nil
}

// getWorkspaceVolumeAndMount 获取远程开发的工作空间挂载
func getWorkspaceVolumeAndMount(
	ws *RemotingWorkspace,
) (volumes []corev1.Volume, volumeMounts []corev1.VolumeMount) {
	// TODO: 调试使用，目前先按照用户项目仓库和分支拼接挂载地址
	dataHostPath := filepath.Join(remoting.HostPath, ws.UserID, ws.GitRepo.GitRepoName, ws.GitRepo.GitRepoRef)
	volumes = []corev1.Volume{
		{
			Name: remoting.VolumeName,
			VolumeSource: corev1.VolumeSource{
				HostPath: &corev1.HostPathVolumeSource{
					Path: dataHostPath,
				},
			},
		},
	}

	volumeMounts = []corev1.VolumeMount{
		{
			Name:      remoting.VolumeName,
			MountPath: remoting.VolumeMountPath,
		},
	}

	return volumes, volumeMounts
}

// TODO: 调试使用，目前先定死label的名称
func getRemotingDispatchLabel(
	coreName string,
	taskId string,
	taskAction types.TaskAction,
	labelType types.TaskLabelType,
) map[string]string {
	labels := map[string]string{}
	labels[remoting.RemotingCoreLabel] = coreName

	labels[remoting.RemotingWatchLabel] =
		fmt.Sprintf("%s-%s-%s", taskId, string(labelType), string(taskAction))

	return labels
}

// TODO：方便调试目前先写死
func getRemotingEnvs(ws *RemotingWorkspace) (envs []corev1.EnvVar) {
	// 先添加Remoting需要的环境变量
	remotingEnvs := map[string]string{
		"DEVOPS_REMOTING_IDE_PORT":            strconv.Itoa(remoting.RemotingVscodeWebPort),
		"DEVOPS_REMOTING_WORKSPACE_ROOT_PATH": remoting.VolumeMountPath,
		"DEVOPS_REMOTING_GIT_REPO_ROOT_PATH":  remoting.VolumeMountPath + "/" + ws.GitRepo.GitRepoName,
		"DEVOPS_REMOTING_GIT_USERNAME":        ws.GitUsername,
		"DEVOPS_REMOTING_GIT_EMAIL":           ws.GitEmail,
	}
	for key, value := range remotingEnvs {
		envs = append(envs, corev1.EnvVar{
			Name:  key,
			Value: value,
		})
	}

	// 添加用户环境变量
	for key, value := range ws.UserEnvs {
		envs = append(envs, corev1.EnvVar{
			Name:  key,
			Value: value,
		})
	}

	return envs
}


func DeleteWorkspace(workspaceId string) (taskId string, err error) {
	taskId = generateTaskId()

	if err := mysql.InsertTask(types.Task{
		TaskId:     taskId,
		TaskKey:    workspaceId,
		TaskBelong: types.TaskBelongRemotingWorkspace,
		Action:     types.TaskActionDelete,
		Status:     types.TaskWaiting,
		Message:    nil,
		ActionTime: time.Now(),
		UpdateTime: time.Now(),
	}); err != nil {
		return "", err
	}

	go task.DoDeleteWorkspace(taskId, workspaceId)

	return taskId, nil
}

func GetRemotingUrl(workspaceId string) (string, error){
	svc, err:=kubeclient.GetService("service-"+workspaceId)
	if err != nil{
		return "", err
	}

	return svc.
}