module github.com/TencentBlueKing/bk-ci/agent

go 1.19

require (
	github.com/TencentBlueKing/bk-ci/agentcommon v0.0.0-00010101000000-000000000000
	github.com/docker/docker v24.0.9+incompatible
	github.com/gofrs/flock v0.8.1
	github.com/nicksnyder/go-i18n/v2 v2.2.1
	github.com/pkg/errors v0.9.1
	github.com/sirupsen/logrus v1.9.3
	golang.org/x/text v0.21.0
	gopkg.in/ini.v1 v1.67.0
	gopkg.in/natefinch/lumberjack.v2 v2.2.1 // indirect
	gotest.tools/v3 v3.5.0
)

require (
	github.com/Microsoft/go-winio v0.6.0 // indirect
	github.com/docker/go-units v0.5.0 // indirect
	github.com/go-ole/go-ole v1.2.6 // indirect
	github.com/gogo/protobuf v1.3.2 // indirect
	github.com/google/go-cmp v0.6.0 // indirect
	github.com/imdario/mergo v0.3.12 // indirect
	github.com/lufia/plan9stats v0.0.0-20220913051719-115f729f3c8c // indirect
	github.com/mitchellh/mapstructure v1.5.0 // indirect
	github.com/opencontainers/go-digest v1.0.0 // indirect
	github.com/opencontainers/image-spec v1.1.0-rc2.0.20221005185240-3a7f492d3f1b // indirect
	github.com/power-devops/perfstat v0.0.0-20220216144756-c35f1ee13d7c // indirect
	github.com/rogpeppe/go-internal v1.6.2 // indirect
	github.com/tklauser/go-sysconf v0.3.12 // indirect
	github.com/tklauser/numcpus v0.6.1 // indirect
	github.com/xeipuuv/gojsonpointer v0.0.0-20180127040702-4e3ac2762d5f // indirect
	github.com/xeipuuv/gojsonreference v0.0.0-20180127040603-bd5ef7bd5415 // indirect
	github.com/yusufpapurcu/wmi v1.2.4 // indirect
	golang.org/x/net v0.25.0 // indirect
	golang.org/x/sys v0.28.0
	gopkg.in/yaml.v2 v2.4.0 // indirect
)

require (
	github.com/fsouza/go-dockerclient v1.9.7
	github.com/gorilla/websocket v1.5.0
	golang.org/x/sync v0.10.0
)

require (
	github.com/Azure/go-ansiterm v0.0.0-20210617225240-d185dfc1b5a1 // indirect
	github.com/BurntSushi/toml v1.2.1 // indirect
	github.com/containerd/containerd v1.6.26 // indirect
	github.com/docker/distribution v2.8.2+incompatible // indirect
	github.com/google/shlex v0.0.0-20191202100458-e7afc7fbc510 // indirect
	github.com/klauspost/compress v1.15.11 // indirect
	github.com/kr/pretty v0.3.0 // indirect
	github.com/moby/patternmatcher v0.5.0 // indirect
	github.com/moby/sys/sequential v0.5.0 // indirect
	github.com/moby/term v0.0.0-20210619224110-3f7ff695adc6 // indirect
	github.com/morikuni/aec v1.0.0 // indirect
	github.com/opencontainers/runc v1.1.14 // indirect
	github.com/rickb777/date v1.14.2 // indirect
	github.com/rickb777/plural v1.2.2 // indirect
	github.com/shoenig/go-m1cpu v0.1.6 // indirect
	github.com/xeipuuv/gojsonschema v1.2.0 // indirect
	golang.org/x/mod v0.17.0 // indirect
	golang.org/x/term v0.27.0 // indirect
	golang.org/x/time v0.3.0 // indirect
	golang.org/x/tools v0.21.1-0.20240508182429-e35e4ccd0d2d // indirect
	golang.org/x/xerrors v0.0.0-20220907171357-04be3eba64a2 // indirect
	gopkg.in/check.v1 v1.0.0-20201130134442-10cb98267c6c // indirect
)

require (
	// 非稳定库，目前只在windows升级中简单使用且主要做对go-ole的封装简化，大规模使用前需要评估
	github.com/capnspacehook/taskmaster v0.0.0-20210519235353-1629df7c85e9
	github.com/docker/cli v23.0.1+incompatible
	github.com/docker/go-connections v0.4.0
	github.com/kardianos/service v1.2.2
	github.com/shirou/gopsutil/v4 v4.24.5
	github.com/spf13/pflag v1.0.5
)

replace github.com/TencentBlueKing/bk-ci/agentcommon => ../common
