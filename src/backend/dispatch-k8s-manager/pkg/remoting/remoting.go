package remoting

// TODO: 调试使用目前先写死
const (
	VolumeName                = "remoting-host"
	VolumeMountPath           = "/data/landun/workspace"
	HostPath                  = "/data/landun/workspace"
	RemotingCoreLabel         = "bkci.dispatch.kubenetes.remoting/core"
	RemotingWatchLabel        = "bkci.dispatch.kubenetes.remoting/watch-task"
	RemotingImage             = "mirrors.tencent.com/ruotiantang/devops-remoting-demo:v3"
	RemotingVscodeWebPort     = 23000
	RemotingVscodeSSHPort     = 23001
	RemotingApiPort           = 22999
	RemotingServiceWebNodePortName = "webvscodeport"
	RemotingServiceSSHNodePortName = "sshport"
	RemotingServiceApiNodePortName = "apiport"
)
