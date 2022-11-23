package apis

import (
	"disaptch-k8s-manager/pkg/apiserver/service"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/pkg/errors"
)

func initRemotingApis(r *gin.RouterGroup) {
	remoting := r.Group("/remoting")
	{
		workspace := remoting.Group("/workspaces")
		{
			workspace.POST("", createWorkspace)
			workspace.DELETE("/:workspaceId", deleteWorkspace)
		}
	}
}

// @Tags  remoting
// @Summary  创建工作空间
// @Accept  json
// @Product  json
// @Param  Devops-Token  header  string  true "凭证信息"
// @Param  workspace  body  service.RemotingWorkspace  true  "工作空间信息"
// @Success 200 {object} types.Result{data=service.TaskId} "任务ID"
// @Router /remoting/workspaces [post]
func createWorkspace(c *gin.Context) {
	ws := &service.RemotingWorkspace{}

	if err := c.BindJSON(ws); err != nil {
		fail(c, http.StatusBadRequest, err)
		return
	}

	taskId, err := service.CreateRemotingWorkspace(ws)
	if err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}

	ok(c, service.TaskId{TaskId: taskId})
}

// @Tags  remoting
// @Summary  删除工作空间
// @Accept  json
// @Product  json
// @Param  Devops-Token  header  string  true "凭证信息"
// @Param  workspaceId  path  string  true  "工作空间ID"
// @Success 200 {object} types.Result{data=service.TaskId} "任务ID"
// @Router /remoting/workspaces/{workspaceId} [delete]
func deleteWorkspace(c *gin.Context) {
	workspaceId := c.Param("workspaceId")

	if !checkWorkspaceId(c, workspaceId) {
		return
	}

	taskId, err := service.DeleteWorkspace(workspaceId)
	if err != nil {
		fail(c, http.StatusInternalServerError, err)
		return
	}

	ok(c, service.TaskId{TaskId: taskId})
}

func checkWorkspaceId(c *gin.Context, workspaceId string) bool {
	if workspaceId == "" {
		fail(c, http.StatusBadRequest, errors.New("workspaceId不能为空"))
		return false
	}

	return true
}
