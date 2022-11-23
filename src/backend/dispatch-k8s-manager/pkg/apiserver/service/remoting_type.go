package service

type RemotingWorkspace struct {
	WorkspaceID string             `json:"workspaceId"`
	UserID      string             `json:"userId"`
	GitRepo     RemotingGitRepo    `json:"gitRepo"`
	GitUsername string             `json:"gitUsername"`
	GitEmail    string             `json:"gitEmail"`
	UserEnvs    map[string]string  `json:"userEnvs"`
	UserFiles   []RemotingUserFile `json:"userFiles"`
}

type RemotingUserFile struct {
	From string `json:"from"`
	To   string `json:"to"`
}

type RemotingGitRepo struct {
	GitRepoName string `json:"gitRepoName"`
	GitRepoRef  string `json:"gitRepoRef"`
}
