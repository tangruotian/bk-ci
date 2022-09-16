package util

import (
	"reflect"
	"testing"
)

func Test_findExpressions(t *testing.T) {
	type args struct {
		condition string
		levelMax  int
	}
	tests := []struct {
		name       string
		args       args
		wantResult [][]expressionBlock
	}{
		{
			name: "${{parameters.xxx == xxxx}}",
			args: args{
				condition: "${{parameters.xxx == xxxx}}",
				levelMax:  1,
			},
			wantResult: [][]expressionBlock{{{0, 26}}},
		},
		{
			name: " ${{parameters.xxx == xxxx}}",
			args: args{
				condition: " ${{parameters.xxx == xxxx}}",
				levelMax:  1,
			},
			wantResult: [][]expressionBlock{{{1, 27}}},
		},
		{
			name: "parameters.xxx == xxxx }} ",
			args: args{
				condition: "parameters.xxx == xxxx }} ",
				levelMax:  1,
			},
			wantResult: nil,
		},
		{
			name: "aaa: xx == ${{ parameters.xxx == xxxx}} == ${{xxxx }} !${xx}}",
			args: args{
				condition: "aaa: xx == ${{ parameters.xxx == xxxx}} == ${{xxxx }} !${xx}}",
				levelMax:  1,
			},
			wantResult: [][]expressionBlock{{{11, 38}, {43, 52}}},
		},
		{
			name: "${{ 4  ${{ 2 ${{ 1 }} }} ${{ 3 }} }} ",
			args: args{
				condition: "${{ 4  ${{ 2 ${{ 1 }} }} ${{ 3 }} }} ",
				levelMax:  1,
			},
			wantResult: [][]expressionBlock{{{13, 20}}},
		},
		{
			name: "${{ 4  2 ${{ 1 }} }} ${{ 3 }}",
			args: args{
				condition: "${{ 4  2 ${{ 1 }} }} ${{ 3 }}",
				levelMax:  1,
			},
			wantResult: [][]expressionBlock{{{9, 16}}},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if gotResult := findExpressions(tt.args.condition, tt.args.levelMax); !reflect.DeepEqual(gotResult, tt.wantResult) {
				t.Errorf("findExpressions() = %v, want %v", gotResult, tt.wantResult)
			}
		})
	}
}
