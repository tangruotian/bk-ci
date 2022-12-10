package kubeclient

import (
	"context"
	"disaptch-k8s-manager/pkg/config"
	"disaptch-k8s-manager/pkg/remoting"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"
)

// TODO：目前调试使用直接写死
func CreateRemotingNodePortService(name, workloadName string) error {
	_, err := kubeClient.CoreV1().Services(config.Config.Kubernetes.NameSpace).Create(
		context.TODO(),
		&corev1.Service{
			TypeMeta: metav1.TypeMeta{
				Kind:       "Service",
				APIVersion: "v1",
			},
			ObjectMeta: metav1.ObjectMeta{
				Name:      name,
				Namespace: config.Config.Kubernetes.NameSpace,
			},
			Spec: corev1.ServiceSpec{
				Type: corev1.ServiceTypeNodePort,
				Selector: map[string]string{
					remoting.RemotingWorkspaceCoreLabel: workloadName,
				},
				Ports: []corev1.ServicePort{
					{
						Name:     remoting.RemotingServiceWebNodePortName,
						Protocol: corev1.ProtocolTCP,
						Port:     remoting.RemotingVscodeWebPort,
						TargetPort: intstr.IntOrString{
							Type:   intstr.Int,
							IntVal: remoting.RemotingVscodeWebPort,
						},
					},
					{
						Name:     remoting.RemotingServiceSSHNodePortName,
						Protocol: corev1.ProtocolTCP,
						Port:     remoting.RemotingApiPort,
						TargetPort: intstr.IntOrString{
							Type:   intstr.Int,
							IntVal: remoting.RemotingApiPort,
						},
					},
					{
						Name:     remoting.RemotingServiceApiNodePortName,
						Protocol: corev1.ProtocolTCP,
						Port:     remoting.RemotingVscodeSSHPort,
						TargetPort: intstr.IntOrString{
							Type:   intstr.Int,
							IntVal: remoting.RemotingVscodeSSHPort,
						},
					},
				},
			},
		},
		metav1.CreateOptions{},
	)

	return err
}

func DeleteService(name string) error {
	err := kubeClient.CoreV1().Services(config.Config.Kubernetes.NameSpace).Delete(
		context.TODO(),
		name,
		metav1.DeleteOptions{},
	)

	return err
}

func GetService(name string) (*corev1.Service, error) {
	service, err := kubeClient.CoreV1().Services(config.Config.Kubernetes.NameSpace).Get(
		context.TODO(),
		name,
		metav1.GetOptions{},
	)

	return service, err
}
