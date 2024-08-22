/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.common.pipeline.dialect

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class PipelineDialectTest {

    @Test
    fun testGetDialect() {
        val actual = PipelineDialectEnums.getDialect(null, null, null)
        Assertions.assertEquals(PipelineDialectEnums.CLASSIC.dialect, actual)

        val actual2 = PipelineDialectEnums.getDialect(
            PipelineDialectEnums.CLASSIC.name,
            null,
            null
        )
        Assertions.assertEquals(PipelineDialectEnums.CLASSIC.dialect, actual2)

        val actual3 = PipelineDialectEnums.getDialect(
            null,
            true,
            null
        )
        Assertions.assertEquals(PipelineDialectEnums.CLASSIC.dialect, actual3)

        val actual4 = PipelineDialectEnums.getDialect(
            null,
            true,
            PipelineDialectEnums.CONSTRAINED.name
        )
        Assertions.assertEquals(PipelineDialectEnums.CLASSIC.dialect, actual4)

        val actual5 = PipelineDialectEnums.getDialect(
            null,
            false,
            PipelineDialectEnums.CONSTRAINED.name
        )
        Assertions.assertEquals(PipelineDialectEnums.CONSTRAINED.dialect, actual5)

        val actual6 = PipelineDialectEnums.getDialect(
            PipelineDialectEnums.CLASSIC.name,
            true,
            PipelineDialectEnums.CONSTRAINED.name
        )
        Assertions.assertEquals(PipelineDialectEnums.CLASSIC.dialect, actual6)

        val actual7 = PipelineDialectEnums.getDialect(
            PipelineDialectEnums.CLASSIC.name,
            false,
            PipelineDialectEnums.CONSTRAINED.name
        )
        Assertions.assertEquals(PipelineDialectEnums.CONSTRAINED.dialect, actual7)
    }
}
