/*
 * © Copyright 2013 EntIT Software LLC
 *  Certain versions of software and/or documents (“Material”) accessible here may contain branding from
 *  Hewlett-Packard Company (now HP Inc.) and Hewlett Packard Enterprise Company.  As of September 1, 2017,
 *  the Material is now offered by Micro Focus, a separately owned and operated company.  Any reference to the HP
 *  and Hewlett Packard Enterprise/HPE marks is historical in nature, and the HP and Hewlett Packard Enterprise/HPE
 *  marks are the property of their respective owners.
 * __________________________________________________________________
 * MIT License
 *
 * Copyright (c) 2018 Micro Focus Company, L.P.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ___________________________________________________________________
 *
 */

package com.hpe.application.automation.tools.octane.actions.dto;

import javax.xml.bind.annotation.*;

/**
 * This file represents scm resource for sending to Octane
 */
@XmlRootElement(name = "dataTable")
@XmlAccessorType(XmlAccessType.FIELD)
public class ScmResourceFile implements SupportsMoveDetection, SupportsOctaneStatus {

    @XmlTransient
    private String id;
    @XmlTransient
    private String type = "scm_resource_file";
    @XmlTransient
    private BaseRefEntity scmRepository;

    //PROPERTIES FOR MOVED ENTITY
    @XmlAttribute
    private String changeSetSrc;
    @XmlAttribute
    private String changeSetDst;
    @XmlAttribute
    private String oldRelativePath;
    @XmlAttribute
    private String oldName;
    @XmlAttribute
    private Boolean isMoved;

    //don't serialized to server
    @XmlAttribute
    private OctaneStatus octaneStatus;

    private String name;

    private String relativePath;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public BaseRefEntity getScmRepository() {
        return scmRepository;
    }

    public void setScmRepository(BaseRefEntity scmRepository) {
        this.scmRepository = scmRepository;
    }

    @Override
    public String getChangeSetSrc() {
        return changeSetSrc;
    }
    @Override
    public void setChangeSetSrc(String changeSetSrc) {
        this.changeSetSrc = changeSetSrc;
    }

    @Override
    public String getChangeSetDst() {
        return changeSetDst;
    }

    @Override
    public void setChangeSetDst(String changeSetDst) {
        this.changeSetDst = changeSetDst;
    }

    public String getOldRelativePath() {
        return oldRelativePath;
    }

    public void setOldRelativePath(String oldRelativePath) {
        this.oldRelativePath = oldRelativePath;
    }

    public String getOldName() {
        return oldName;
    }

    public void setOldName(String oldName) {
        this.oldName = oldName;
    }

    public Boolean getIsMoved() {
        return isMoved == null ? false : isMoved;
    }

    public void setIsMoved(Boolean moved) {
        isMoved = moved;
    }

    @Override
    public OctaneStatus getOctaneStatus() {
        return octaneStatus;
    }

    public void setOctaneStatus(OctaneStatus octaneStatus) {
        this.octaneStatus = octaneStatus;
    }
}
