/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.fontbox.ttf.table.common;

/**
 * This class models the
 * <a href="https://docs.microsoft.com/en-us/typography/opentype/spec/chapter2#lookup-table">Lookup Sub-Table</a> in the
 * Open Type layout common tables.
 * 
 * @author Palash Ray
 *
 */
public abstract class LookupSubTable
{
    private final int substFormat;
    private final CoverageTable coverageTable;

    public LookupSubTable(int substFormat, CoverageTable coverageTable)
    {
        this.substFormat = substFormat;
        this.coverageTable = coverageTable;
    }

    public abstract int doSubstitution(int gid, int coverageIndex);

    public abstract int[] getAlternatives(int gid, int coverageIndex);

    public int getSubstFormat()
    {
        return substFormat;
    }

    public CoverageTable getCoverageTable()
    {
        return coverageTable;
    }
}