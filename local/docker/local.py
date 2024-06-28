import requests
import json

url = 'http://localhost/api/service/environment/thirdPartyAgent/local_gen_agent'
params = {
    'projectId': 'demo',
    'userId': 'admin'
}
headers = {
    'X-DEVOPS-UID': 'admin'
}

response = requests.post(url, params=params, headers=headers)
json_data = response.json()

agent_hash_id = json_data.get("data")

config = """
devops.project.id=demo
devops.agent.id=
devops.agent.secret.key=local
landun.gateway=
landun.fileGateway=
devops.parallel.task.count=4
landun.env=LOCAL
devops.slave.user=root
devops.agent.request.timeout.sec=5
devops.agent.detect.shell=true
devops.agent.ignoreLocalIps=127.0.0.1,192.168.255.10,192.168.10.255
devops.agent.logs.keep.hours=96
devops.agent.jdk.dir.path=/agent/jdk
devops.docker.parallel.task.count=4
devops.docker.enable=false
devops.language=zh_CN
devops.imagedebug.portrange=30000-32767
devops.pipeline.enable=false
"""

config_dict = {}
for line in config.strip().split('\n'):
    key, value = line.split('=')
    config_dict[key.strip()] = value.strip()

config_dict['devops.agent.id'] = 'menbabxp'

new_config = '\n'.join([f"{key}={value}" for key, value in config_dict.items()])

with open('.agent.properties', 'w') as file:
    file.write(new_config)