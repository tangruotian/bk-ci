package com.tencent.devops.common.expression.expression

import com.tencent.devops.common.expression.ContextNotFoundException
import com.tencent.devops.common.expression.ExecutionContext
import com.tencent.devops.common.expression.ExpressionParser
import com.tencent.devops.common.expression.context.ArrayContextData
import com.tencent.devops.common.expression.context.ContextValueNode
import com.tencent.devops.common.expression.context.DictionaryContextData
import com.tencent.devops.common.expression.expression.sdk.NamedValueInfo
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Suppress("ComplexMethod", "LongMethod", "MaxLineLength")
@DisplayName("测试EvaluationOptions配置的不同选项")
class EvaluationOptionsTest {
    @DisplayName("exceptionInsteadOfNull相关场景测试")
    @Nested
    inner class ExceptionTest {
        @DisplayName("配置了exceptionInsteadOfNull")
        @ParameterizedTest
        @ValueSource(
            strings = [
                "string => string",
                "obj.a => a",
                "array[1] => 1"
            ]
        )
        fun exceptionInsteadOfNull(group: String) {
            val (exp, exArg) = group.split(" => ")
            val exception = assertThrows<ContextNotFoundException> {
                ExpressionParser.createTree(exp, null, nameValue, null)!!
                    .evaluate(null, ev, EvaluationOptions(true), null).value
            }
            Assertions.assertEquals(ContextNotFoundException.trace(exArg).message, exception.message)
        }

        @DisplayName("不配置exceptionInsteadOfNull")
        @Test
        fun noExceptionInsteadOfNull() {
        }

        private val nameValue = mutableListOf<NamedValueInfo>().apply {
            add(NamedValueInfo("string", ContextValueNode()))
            add(NamedValueInfo("obj", ContextValueNode()))
            add(NamedValueInfo("array", ContextValueNode()))
        }
        private val ev = ExecutionContext(DictionaryContextData().apply {
            add("obj", DictionaryContextData())
            add("array", ArrayContextData())
        })
    }
}