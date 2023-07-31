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

package org.apache.fontbox.ttf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.fontbox.ttf.table.common.CoverageTable;
import org.apache.fontbox.ttf.table.common.CoverageTableFormat1;
import org.apache.fontbox.ttf.table.common.CoverageTableFormat2;
import org.apache.fontbox.ttf.table.common.LookupSubTable;
import org.apache.fontbox.ttf.table.common.RangeRecord;
import org.apache.fontbox.ttf.table.gsub.LookupTypeMultipleSubstitutionFormat1;
import org.apache.fontbox.ttf.table.gsub.LookupTypeSingleSubstFormat1;
import org.apache.fontbox.ttf.table.gsub.LookupTypeSingleSubstFormat2;
import org.apache.fontbox.ttf.table.gsub.SequenceTable;
/**
 * A glyph substitution 'GSUB' table in a TrueType or OpenType font.
 *
 * @author Aaron Madlon-Kay
 */
public class GlyphSubstitutionTable extends TTFTable
{
    private static final Log LOG = LogFactory.getLog(GlyphSubstitutionTable.class);

    public static final String TAG = "GSUB";

    private LinkedHashMap<String, ScriptTable> scriptList;
    // featureList and lookupList are not maps because we need to index into them
    private FeatureRecord[] featureList;
    private LookupTable[] lookupList;

    private final Map<Integer, Integer> lookupCache = new HashMap<Integer, Integer>();
    private final Map<Integer, Integer> reverseLookup = new HashMap<Integer, Integer>();

    private String lastUsedSupportedScript;

    GlyphSubstitutionTable(TrueTypeFont font)
    {
        super(font);
    }

    @Override
    void read(TrueTypeFont ttf, TTFDataStream data) throws IOException
    {
        long start = data.getCurrentPosition();
        @SuppressWarnings("unused")
        int majorVersion = data.readUnsignedShort();
        int minorVersion = data.readUnsignedShort();
        int scriptListOffset = data.readUnsignedShort();
        int featureListOffset = data.readUnsignedShort();
        int lookupListOffset = data.readUnsignedShort();
        @SuppressWarnings("unused")
        long featureVariationsOffset = -1L;
        if (minorVersion == 1L)
        {
            featureVariationsOffset = data.readUnsignedInt();
        }

        scriptList = readScriptList(data, start + scriptListOffset);
        featureList = readFeatureList(data, start + featureListOffset);
        lookupList = readLookupList(data, start + lookupListOffset);

        initialized = true;
    }

    LinkedHashMap<String, ScriptTable> readScriptList(TTFDataStream data, long offset) throws IOException
    {
        data.seek(offset);
        int scriptCount = data.readUnsignedShort();
        ScriptRecord[] scriptRecords = new ScriptRecord[scriptCount];
        int[] scriptOffsets = new int[scriptCount];
        for (int i = 0; i < scriptCount; i++)
        {
            ScriptRecord scriptRecord = new ScriptRecord();
            scriptRecord.scriptTag = data.readString(4);
            scriptOffsets[i] = data.readUnsignedShort();
            scriptRecords[i] = scriptRecord;
        }
        for (int i = 0; i < scriptCount; i++)
        {
            scriptRecords[i].scriptTable = readScriptTable(data, offset + scriptOffsets[i]);
        }
        LinkedHashMap<String, ScriptTable> resultScriptList = new LinkedHashMap<String, ScriptTable>(scriptCount);
        for (ScriptRecord scriptRecord : scriptRecords)
        {
            resultScriptList.put(scriptRecord.scriptTag, scriptRecord.scriptTable);
        }
        return resultScriptList;
    }

    ScriptTable readScriptTable(TTFDataStream data, long offset) throws IOException
    {
        data.seek(offset);
        ScriptTable scriptTable = new ScriptTable();
        int defaultLangSys = data.readUnsignedShort();
        int langSysCount = data.readUnsignedShort();
        LangSysRecord[] langSysRecords = new LangSysRecord[langSysCount];
        int[] langSysOffsets = new int[langSysCount];
        String prevLangSysTag = "";
        for (int i = 0; i < langSysCount; i++)
        {
            LangSysRecord langSysRecord = new LangSysRecord();
            langSysRecord.langSysTag = data.readString(4);
            if (i > 0 && langSysRecord.langSysTag.compareTo(prevLangSysTag) <= 0)
            {
                // PDFBOX-4489: catch corrupt file
                // https://docs.microsoft.com/en-us/typography/opentype/spec/chapter2#slTbl_sRec
                throw new IOException("LangSysRecords not alphabetically sorted by LangSys tag: " +
                          langSysRecord.langSysTag + " <= " + prevLangSysTag);
            }
            langSysOffsets[i] = data.readUnsignedShort();
            langSysRecords[i] = langSysRecord;
            prevLangSysTag = langSysRecord.langSysTag;
        }
        if (defaultLangSys != 0)
        {
            scriptTable.defaultLangSysTable = readLangSysTable(data, offset + defaultLangSys);
        }
        for (int i = 0; i < langSysCount; i++)
        {
            langSysRecords[i].langSysTable = readLangSysTable(data, offset + langSysOffsets[i]);
        }
        scriptTable.langSysTables = new LinkedHashMap<String, LangSysTable>(langSysCount);
        for (LangSysRecord langSysRecord : langSysRecords)
        {
            scriptTable.langSysTables.put(langSysRecord.langSysTag, langSysRecord.langSysTable);
        }
        return scriptTable;
    }

    LangSysTable readLangSysTable(TTFDataStream data, long offset) throws IOException
    {
        data.seek(offset);
        LangSysTable langSysTable = new LangSysTable();
        @SuppressWarnings("unused")
        int lookupOrder = data.readUnsignedShort();
        langSysTable.requiredFeatureIndex = data.readUnsignedShort();
        int featureIndexCount = data.readUnsignedShort();
        langSysTable.featureIndices = new int[featureIndexCount];
        for (int i = 0; i < featureIndexCount; i++)
        {
            langSysTable.featureIndices[i] = data.readUnsignedShort();
        }
        return langSysTable;
    }

    FeatureRecord[] readFeatureList(TTFDataStream data, long offset) throws IOException
    {
        data.seek(offset);
        int featureCount = data.readUnsignedShort();
        FeatureRecord[] featureRecords = new FeatureRecord[featureCount];
        int[] featureOffsets = new int[featureCount];
        String prevFeatureTag = "";
        for (int i = 0; i < featureCount; i++)
        {
            FeatureRecord featureRecord = new FeatureRecord();
            featureRecord.featureTag = data.readString(4);
            if (i > 0 && featureRecord.featureTag.compareTo(prevFeatureTag) < 0)
            {
                // catch corrupt file
                // https://docs.microsoft.com/en-us/typography/opentype/spec/chapter2#flTbl
                if (featureRecord.featureTag.matches("\\w{4}") && prevFeatureTag.matches("\\w{4}"))
                {
                    // ArialUni.ttf has many warnings but isn't corrupt, so we assume that only
                    // strings with trash characters indicate real corruption
                    LOG.debug("FeatureRecord array not alphabetically sorted by FeatureTag: " +
                               featureRecord.featureTag + " < " + prevFeatureTag);
                }
                else
                {
                    LOG.warn("FeatureRecord array not alphabetically sorted by FeatureTag: " +
                               featureRecord.featureTag + " < " + prevFeatureTag);
                    return new FeatureRecord[0];
                }                
            }
            featureOffsets[i] = data.readUnsignedShort();
            featureRecords[i] = featureRecord;
            prevFeatureTag = featureRecord.featureTag;
        }
        for (int i = 0; i < featureCount; i++)
        {
            featureRecords[i].featureTable = readFeatureTable(data, offset + featureOffsets[i]);
        }
        return featureRecords;
    }

    FeatureTable readFeatureTable(TTFDataStream data, long offset) throws IOException
    {
        data.seek(offset);
        FeatureTable featureTable = new FeatureTable();
        @SuppressWarnings("unused")
        int featureParams = data.readUnsignedShort();
        int lookupIndexCount = data.readUnsignedShort();
        featureTable.lookupListIndices = new int[lookupIndexCount];
        for (int i = 0; i < lookupIndexCount; i++)
        {
            featureTable.lookupListIndices[i] = data.readUnsignedShort();
        }
        return featureTable;
    }

    LookupTable[] readLookupList(TTFDataStream data, long offset) throws IOException
    {
        data.seek(offset);
        int lookupCount = data.readUnsignedShort();
        int[] lookups = new int[lookupCount];
        for (int i = 0; i < lookupCount; i++)
        {
            lookups[i] = data.readUnsignedShort();
        }
        LookupTable[] lookupTables = new LookupTable[lookupCount];
        for (int i = 0; i < lookupCount; i++)
        {
            lookupTables[i] = readLookupTable(data, offset + lookups[i]);
        }
        return lookupTables;
    }

    private LookupSubTable readLookupSubtable(TTFDataStream data, long offset, int lookupType) throws IOException
    {
        switch (lookupType)
        {
            case 1:
                // Single Substitution Subtable
                // https://docs.microsoft.com/en-us/typography/opentype/spec/gsub#SS
                return readSingleLookupSubTable(data, offset);
            case 2:
                // Multiple Substitution Subtable
                // https://learn.microsoft.com/en-us/typography/opentype/spec/gsub#lookuptype-2-multiple-substitution-subtable
                return readMultipleSubstitutionSubtable(data, offset);
            // case 4:
                // Ligature Substitution Subtable
                // https://docs.microsoft.com/en-us/typography/opentype/spec/gsub#LS
                // return readLigatureSubstitutionSubtable(data, offset);

                // when creating a new LookupSubTable derived type, don't forget to add a "switch"
                // in readLookupTable() and add the type in GlyphSubstitutionDataExtractor.extractData()

            default:
                // Other lookup types are not supported
                LOG.debug("Type " + lookupType
                        + " GSUB lookup table is not supported and will be ignored");
                return null;
                //TODO next: implement type 6
                // https://learn.microsoft.com/en-us/typography/opentype/spec/gsub#lookuptype-6-chained-contexts-substitution-subtable
                // see e.g. readChainedContextualSubTable in Apache FOP
                // https://github.com/apache/xmlgraphics-fop/blob/1323c2e3511eb23c7dd9b8fb74463af707fa972d/fop-core/src/main/java/org/apache/fop/complexscripts/fonts/OTFAdvancedTypographicTableReader.java#L898
        }
    }

    LookupTable readLookupTable(TTFDataStream data, long offset) throws IOException
    {
        data.seek(offset);
        int lookupType = data.readUnsignedShort();
        int lookupFlag = data.readUnsignedShort();
        int subTableCount = data.readUnsignedShort();
        int[] subTableOffsets = new int[subTableCount];
        for (int i = 0; i < subTableCount; i++)
        {
            subTableOffsets[i] = data.readUnsignedShort();
        }

        int markFilteringSet = 0;
        if ((lookupFlag & 0x0010) != 0)
        {
            markFilteringSet = data.readUnsignedShort();
        }
        LookupSubTable[] subTables = new LookupSubTable[subTableCount];
        switch (lookupType)
        {
        case 1:
        case 2:
        case 4:
            for (int i = 0; i < subTableCount; i++)
            {
                subTables[i] = readLookupSubtable(data, offset + subTableOffsets[i], lookupType);
            }
            break;
        case 7:
            // Extension Substitution
            // https://learn.microsoft.com/en-us/typography/opentype/spec/gsub#ES
            for (int i = 0; i < subTableCount; i++)
            {
                long baseOffset = data.getCurrentPosition();
                int substFormat = data.readUnsignedShort(); // always 1
                if (substFormat != 1)
                {
                    LOG.error("The expected SubstFormat for ExtensionSubstFormat1 subtable is " +
                            substFormat + " but should be 1");
                    continue;
                }
                int extensionLookupType = data.readUnsignedShort();
                long extensionOffset = data.readUnsignedInt();
                subTables[i] = readLookupSubtable(data, baseOffset + extensionOffset, extensionLookupType);
            }
            break;
        default:
            // Other lookup types are not supported
            LOG.debug("Type " + lookupType + " GSUB lookup table is not supported and will be ignored");
        }
        return new LookupTable(lookupType, lookupFlag, markFilteringSet, subTables);
    }

    private LookupSubTable readSingleLookupSubTable(TTFDataStream data, long offset) throws IOException
    {
        data.seek(offset);
        int substFormat = data.readUnsignedShort();
        switch (substFormat)
        {
        case 1:
        {
            // LookupType 1: Single Substitution Subtable
            // https://docs.microsoft.com/en-us/typography/opentype/spec/gsub#11-single-substitution-format-1
            int coverageOffset = data.readUnsignedShort();
            short deltaGlyphID = data.readSignedShort();
            CoverageTable coverageTable = readCoverageTable(data, offset + coverageOffset);
            return new LookupTypeSingleSubstFormat1(substFormat, coverageTable, deltaGlyphID);
        }
        case 2:
        {
            // Single Substitution Format 2
            // https://docs.microsoft.com/en-us/typography/opentype/spec/gsub#12-single-substitution-format-2
            int coverageOffset = data.readUnsignedShort();
            int glyphCount = data.readUnsignedShort();
            int[] substituteGlyphIDs = new int[glyphCount];
            for (int i = 0; i < glyphCount; i++)
            {
                substituteGlyphIDs[i] = data.readUnsignedShort();
            }
            CoverageTable coverageTable = readCoverageTable(data, offset + coverageOffset);
            return new LookupTypeSingleSubstFormat2(substFormat, coverageTable, substituteGlyphIDs);
        }
        default:
            throw new IOException("Unknown substFormat: " + substFormat);
        }
    }

    private LookupSubTable readMultipleSubstitutionSubtable(TTFDataStream data, long offset)
            throws IOException
    {
        data.seek(offset);
        int substFormat = data.readUnsignedShort();

        if (substFormat != 1)
        {
            throw new IOException(
                    "The expected SubstFormat for LigatureSubstitutionTable is 1");
        }

        int coverage = data.readUnsignedShort();
        int sequenceCount = data.readUnsignedShort();
        int[] sequenceOffsets = new int[sequenceCount];
        for (int i = 0; i < sequenceCount; i++)
        {
            sequenceOffsets[i] = data.readUnsignedShort();
        }

        CoverageTable coverageTable = readCoverageTable(data, offset + coverage);

        if (sequenceCount != coverageTable.getSize())
        {
            throw new IOException(
                    "According to the OpenTypeFont specifications, the coverage count should be equal to the no. of SequenceTables");
        }

        SequenceTable[] sequenceTables = new SequenceTable[sequenceCount];
        for (int i = 0; i < sequenceCount; i++)
        {
            data.seek(offset + sequenceOffsets[i]);
            int glyphCount = data.readUnsignedShort();
            for (int j = 0; j < glyphCount; ++j)
            {
                int[] substituteGlyphIDs = data.readUnsignedShortArray(glyphCount);
                sequenceTables[i] = new SequenceTable(glyphCount, substituteGlyphIDs);
            }
        }

        return new LookupTypeMultipleSubstitutionFormat1(substFormat, coverageTable, sequenceTables);
    }

    CoverageTable readCoverageTable(TTFDataStream data, long offset) throws IOException
    {
        data.seek(offset);
        int coverageFormat = data.readUnsignedShort();
        switch (coverageFormat)
        {
        case 1:
        {
            int glyphCount = data.readUnsignedShort();
            int[] glyphArray = new int[glyphCount];
            for (int i = 0; i < glyphCount; i++)
            {
                glyphArray[i] = data.readUnsignedShort();
            }
            return new CoverageTableFormat1(coverageFormat, glyphArray);
        }
        case 2:
        {
            int rangeCount = data.readUnsignedShort();
            RangeRecord[] rangeRecords = new RangeRecord[rangeCount];
            for (int i = 0; i < rangeCount; i++)
            {
                rangeRecords[i] = readRangeRecord(data);
            }
            return new CoverageTableFormat2(coverageFormat, rangeRecords);

        }
        default:
            // Should not happen (the spec indicates only format 1 and format 2)
            throw new IOException("Unknown coverage format: " + coverageFormat);
        }
    }


    /**
     * Choose from one of the supplied OpenType script tags, depending on what the font supports and
     * potentially on context.
     *
     * @param tags
     * @return The best OpenType script tag
     */
    private String selectScriptTag(String[] tags)
    {
        if (tags.length == 1)
        {
            String tag = tags[0];
            if (OpenTypeScript.INHERITED.equals(tag)
                    || (OpenTypeScript.TAG_DEFAULT.equals(tag) && !scriptList.containsKey(tag)))
            {
                // We don't know what script this should be.
                if (lastUsedSupportedScript == null)
                {
                    // We have no past context and (currently) no way to get future context so we guess.
                    lastUsedSupportedScript = scriptList.keySet().iterator().next();
                }
                // else use past context

                return lastUsedSupportedScript;
            }
        }
        for (String tag : tags)
        {
            if (scriptList.containsKey(tag))
            {
                // Use the first recognized tag. We assume a single font only recognizes one version ("ver. 2")
                // of a single script, or if it recognizes more than one that it prefers the latest one.
                lastUsedSupportedScript = tag;
                return lastUsedSupportedScript;
            }
        }
        return tags[0];
    }

    private Collection<LangSysTable> getLangSysTables(String scriptTag)
    {
        Collection<LangSysTable> result = Collections.emptyList();
        ScriptTable scriptTable = scriptList.get(scriptTag);
        if (scriptTable != null)
        {
            if (scriptTable.defaultLangSysTable == null)
            {
                result = scriptTable.langSysTables.values();
            }
            else
            {
                result = new ArrayList<LangSysTable>(scriptTable.langSysTables.values());
                result.add(scriptTable.defaultLangSysTable);
            }
        }
        return result;
    }

    /**
     * Get a list of {@code FeatureRecord}s from a collection of {@code LangSysTable}s. Optionally
     * filter the returned features by supplying a list of allowed feature tags in
     * {@code enabledFeatures}.
     *
     * Note that features listed as required ({@code LangSysTable#requiredFeatureIndex}) will be
     * included even if not explicitly enabled.
     *
     * @param langSysTables The {@code LangSysTable}s indicating {@code FeatureRecord}s to search
     * for
     * @param enabledFeatures An optional list of feature tags ({@code null} to allow all)
     * @return The indicated {@code FeatureRecord}s
     */
    private List<FeatureRecord> getFeatureRecords(Collection<LangSysTable> langSysTables,
            final List<String> enabledFeatures)
    {
        if (langSysTables.isEmpty())
        {
            return Collections.emptyList();
        }
        List<FeatureRecord> result = new ArrayList<FeatureRecord>();
        for (LangSysTable langSysTable : langSysTables)
        {
            int required = langSysTable.requiredFeatureIndex;
            if (required != 0xffff && required < featureList.length) // if no required features = 0xFFFF
            {
                result.add(featureList[required]);
            }
            for (int featureIndex : langSysTable.featureIndices)
            {
                if (featureIndex < featureList.length &&
                        (enabledFeatures == null ||
                         enabledFeatures.contains(featureList[featureIndex].featureTag)))
                {
                    result.add(featureList[featureIndex]);
                }
            }
        }

        // 'vrt2' supersedes 'vert' and they should not be used together
        // https://www.microsoft.com/typography/otspec/features_uz.htm
        if (containsFeature(result, "vrt2"))
        {
            removeFeature(result, "vert");
        }

        if (enabledFeatures != null && result.size() > 1)
        {
            Collections.sort(result, new Comparator<FeatureRecord>()
            {
                @Override
                public int compare(FeatureRecord o1, FeatureRecord o2)
                {
                    int i1 = enabledFeatures.indexOf(o1.featureTag);
                    int i2 = enabledFeatures.indexOf(o2.featureTag);
                    return i1 < i2 ? -1 : i1 == i2 ? 0 : 1;
                }
            });
        }

        return result;
    }

    private boolean containsFeature(List<FeatureRecord> featureRecords, String featureTag)
    {
        for (FeatureRecord featureRecord : featureRecords)
        {
            if (featureRecord.featureTag.equals(featureTag))
            {
                return true;
            }
        }
        return false;
    }

    private void removeFeature(List<FeatureRecord> featureRecords, String featureTag)
    {
        Iterator<FeatureRecord> iter = featureRecords.iterator();
        while (iter.hasNext())
        {
            if (iter.next().featureTag.equals(featureTag))
            {
                iter.remove();
            }
        }
    }

    private int applyFeature(FeatureRecord featureRecord, int gid)
    {
        for (int lookupListIndex : featureRecord.featureTable.lookupListIndices)
        {
            LookupTable lookupTable = lookupList[lookupListIndex];
            if (lookupTable.lookupType != 1)
            {
                LOG.debug("Skipping GSUB feature '" + featureRecord.featureTag
                        + "' because it requires unsupported lookup table type " + lookupTable.lookupType);
                continue;
            }
            gid = doLookup(lookupTable, gid);
        }
        return gid;
    }

    private int doLookup(LookupTable lookupTable, int gid)
    {
        for (LookupSubTable lookupSubtable : lookupTable.subTables)
        {
            int coverageIndex = lookupSubtable.getCoverageTable().getCoverageIndex(gid);
            if (coverageIndex >= 0)
            {
                return lookupSubtable.doSubstitution(gid, coverageIndex);
            }
        }
        return gid;
    }

    /**
     * Apply glyph substitutions to the supplied gid. The applicable substitutions are determined by
     * the {@code scriptTags} which indicate the language of the gid, and by the list of
     * {@code enabledFeatures}.
     *
     * To ensure that a single gid isn't mapped to multiple substitutions, subsequent invocations
     * with the same gid will return the same result as the first, regardless of script or enabled
     * features.
     *
     * @param gid GID
     * @param scriptTags Script tags applicable to the gid (see {@link OpenTypeScript})
     * @param enabledFeatures list of features to apply
     */
    public int getSubstitution(int gid, String[] scriptTags, List<String> enabledFeatures)
    {
        if (gid == -1)
        {
            return -1;
        }
        Integer cached = lookupCache.get(gid);
        if (cached != null)
        {
            // Because script detection for indeterminate scripts (COMMON, INHERIT, etc.) depends on context,
            // it is possible to return a different substitution for the same input. However we don't want that,
            // as we need a one-to-one mapping.
            return cached;
        }
        String scriptTag = selectScriptTag(scriptTags);
        Collection<LangSysTable> langSysTables = getLangSysTables(scriptTag);
        List<FeatureRecord> featureRecords = getFeatureRecords(langSysTables, enabledFeatures);
        int sgid = gid;
        for (FeatureRecord featureRecord : featureRecords)
        {
            sgid = applyFeature(featureRecord, sgid);
        }
        lookupCache.put(gid, sgid);
        reverseLookup.put(sgid, gid);
        return sgid;
    }

    /**
     * For a substitute-gid (obtained from {@link #getSubstitution(int, String[], List)}), retrieve
     * the original gid.
     *
     * Only gids previously substituted by this instance can be un-substituted. If you are trying to
     * unsubstitute before you substitute, something is wrong.
     *
     * @param sgid Substitute GID
     */
    public int getUnsubstitution(int sgid)
    {
        Integer gid = reverseLookup.get(sgid);
        if (gid == null)
        {
            LOG.warn("Trying to un-substitute a never-before-seen gid: " + sgid);
            return sgid;
        }
        return gid;
    }

    RangeRecord readRangeRecord(TTFDataStream data) throws IOException
    {
        int startGlyphID = data.readUnsignedShort();
        int endGlyphID = data.readUnsignedShort();
        int startCoverageIndex = data.readUnsignedShort();
        return new RangeRecord(startGlyphID, endGlyphID, startCoverageIndex);
    }

    static class ScriptRecord
    {
        // https://www.microsoft.com/typography/otspec/scripttags.htm
        String scriptTag;
        ScriptTable scriptTable;

        @Override
        public String toString()
        {
            return String.format("ScriptRecord[scriptTag=%s]", scriptTag);
        }
    }

    static class ScriptTable
    {
        LangSysTable defaultLangSysTable;
        LinkedHashMap<String, LangSysTable> langSysTables;

        @Override
        public String toString()
        {
            return String.format("ScriptTable[hasDefault=%s,langSysRecordsCount=%d]",
                    defaultLangSysTable != null, langSysTables.size());
        }
    }

    static class LangSysRecord
    {
        // https://www.microsoft.com/typography/otspec/languagetags.htm
        String langSysTag;
        LangSysTable langSysTable;

        @Override
        public String toString()
        {
            return String.format("LangSysRecord[langSysTag=%s]", langSysTag);
        }
    }

    static class LangSysTable
    {
        int requiredFeatureIndex;
        int[] featureIndices;

        @Override
        public String toString()
        {
            return String.format("LangSysTable[requiredFeatureIndex=%d]", requiredFeatureIndex);
        }
    }

    static class FeatureRecord
    {
        String featureTag;
        FeatureTable featureTable;

        @Override
        public String toString()
        {
            return String.format("FeatureRecord[featureTag=%s]", featureTag);
        }
    }

    static class FeatureTable
    {
        int[] lookupListIndices;

        @Override
        public String toString()
        {
            return String.format("FeatureTable[lookupListIndicesCount=%d]",
                    lookupListIndices.length);
        }
    }

    static class LookupTable
    {
        int lookupType;
        int lookupFlag;
        int markFilteringSet;
        LookupSubTable[] subTables;

        public LookupTable(int lookupType, int lookupFlag, int markFilteringSet,
                LookupSubTable[] subTables)
        {
            this.lookupType = lookupType;
            this.lookupFlag = lookupFlag;
            this.markFilteringSet = markFilteringSet;
            this.subTables = subTables;
        }

        @Override
        public String toString()
        {
            return String.format("LookupTable[lookupType=%d,lookupFlag=%d,markFilteringSet=%d]",
                    lookupType, lookupFlag, markFilteringSet);
        }
    }

}
