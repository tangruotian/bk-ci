package util

import (
	"github.com/Tencent/bk-ci/src/agent/src/pkg/worker/common"
	"sort"
)

type expressionBlock struct {
	StartIndex int
	EndIndex   int
}

// findExpressions 寻找语句中包含 ${{}}的表达式的位置，返回成对的位置坐标，并根据优先级排序
// 优先级算法目前暂定为 从里到外，从左到右
// levelMax 返回的最大层数，从深到浅
// 例如: 替换顺序如数字所示 ${{ 4  ${{ 2 ${{ 1 }} }} ${{ 3 }} }}
// return [ 层数次序 [ 括号 ] ]  [[1], [2, 3], [4]]]]
func findExpressions(condition string, levelMax int) (result [][]expressionBlock) {
	stack := common.NewStack()
	index := 0
	chars := []rune(condition)
	levelMap := map[int][]expressionBlock{}
	for index < len(chars) {
		if index+2 < len(chars) && chars[index] == '$' && chars[index+1] == '{' && chars[index+2] == '{' {
			stack.Push(index)
			index += 3
			continue
		}

		if index+1 < len(chars) && chars[index] == '}' && chars[index+1] == '}' {
			start := stack.Pop()
			if start != nil {
				level := stack.Len() + 1
				if _, ok := levelMap[level]; ok {
					levelMap[level] = append(levelMap[level], expressionBlock{start.(int), index + 1})
				} else {
					levelMap[level] = []expressionBlock{{start.(int), index + 1}}
				}
			}
			index += 2
			continue
		}

		index++
	}

	if len(levelMap) == 0 {
		return nil
	}

	max := 0
	listIndex := 0
	var keys []int
	for k := range levelMap {
		keys = append(keys, k)
	}
	sort.Sort(sort.Reverse(sort.IntSlice(keys)))
	for _, level := range keys {
		blocks, ok := levelMap[level]
		if !ok {
			continue
		}
		sort.Sort(expressionSort(blocks))
		for _, block := range blocks {
			if len(result) < listIndex+1 {
				result = append(result, []expressionBlock{block})
			} else {
				result[listIndex] = append(result[listIndex], block)
			}
		}
		listIndex++
		max++
		if max == levelMax {
			break
		}
	}

	return result
}

type expressionSort []expressionBlock

func (e expressionSort) Len() int {
	//返回传入数据的总数
	return len(e)
}

func (e expressionSort) Less(i, j int) bool {
	//按字段比较大小,此处是降序排序
	//返回数组中下标为i的数据是否小于下标为j的数据
	return e[i].StartIndex < e[j].StartIndex
}

func (e expressionSort) Swap(i, j int) {
	//两个对象满足Less()则位置对换
	//表示执行交换数组中下标为i的数据和下标为j的数据
	e[i], e[j] = e[j], e[i]
}
