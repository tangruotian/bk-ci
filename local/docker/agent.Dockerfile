# 编译 worker
FROM konajdk/konajdk:8.0.18 AS worker

ARG WORKER_VERSION

WORKDIR /data
COPY src/backend/ci .

WORKDIR /data/core/worker
RUN sed -i 's/##landun.env##/local/g' worker-agent/src/main/resources/.agent.properties
RUN /data/gradlew -Dci_version=$WORKER_VERSION clean shadowJar --profile

WORKDIR /data
RUN version=`java -cp release/worker-agent.jar com.tencent.devops.agent.AgentVersionKt`
RUN echo "The Worker version: $version"

# 编译agent
FROM golang:1.19.13 AS agent

ARG AGENT_VERSION

WORKDIR /data
COPY . .

WORKDIR /data/src/agent/agent
RUN go env -w GOPROXY=https://goproxy.cn,direct
RUN make AGENT_VERSION=$AGENT_VERSION -f Makefile clean build_linux
RUN version=`./bin/devopsAgent_linux version`
RUN echo "The Agent version: $version"

# 整合到最终镜像
FROM ubuntu:oracular

WORKDIR /agent
# agent相关配置部分
RUN apt -y update && apt -y install python3 && apt -y install python3-requests
COPY ./local/docker/local.py .
RUN python3 local.py
# agent相关脚本
COPY ./support-files/agent-package/script/linux .
# worker
COPY --from=worker /data/release/worker-agent.jar .
# agent
COPY --from=agent /data/src/agent/agent/bin/devopsAgent_linux ./devopsAgent
COPY --from=agent /data/src/agent/agent/bin/devopsDaemon_linux ./devopsDaemon
# jdk
RUN apt -y install wget
RUN wget https://github.com/Tencent/TencentKona-8/releases/download/8.0.9-GA/TencentKona8.0.9.b1_jdk_linux-x86_64_8u322.tar.gz
RUN mkdir -p jdk && tar -xvf TencentKona8.0.9.b1_jdk_linux-x86_64_8u322.tar.gz --strip-components=1 -C ./jdk
# 启动
RUN chmod -R +x . 
CMD ["bash", "-c", "./devopsDaemon"]