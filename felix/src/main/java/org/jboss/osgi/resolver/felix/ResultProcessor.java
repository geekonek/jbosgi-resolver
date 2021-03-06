/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.osgi.resolver.felix;

import java.util.ArrayList;
import java.util.List;

import org.apache.felix.framework.capabilityset.Attribute;
import org.apache.felix.framework.capabilityset.Capability;
import org.apache.felix.framework.capabilityset.Requirement;
import org.apache.felix.framework.resolver.FragmentRequirement;
import org.apache.felix.framework.resolver.Module;
import org.apache.felix.framework.resolver.Wire;
import org.apache.felix.framework.util.Util;
import org.jboss.osgi.resolver.XBundleCapability;
import org.jboss.osgi.resolver.XCapability;
import org.jboss.osgi.resolver.XFragmentHostRequirement;
import org.jboss.osgi.resolver.XModule;
import org.jboss.osgi.resolver.XPackageCapability;
import org.jboss.osgi.resolver.XPackageRequirement;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XWire;
import org.jboss.osgi.resolver.spi.AbstractModule;
import org.jboss.osgi.resolver.spi.AbstractPackageRequirement;

/**
 * A processor for the resolver results.
 * 
 * A mandatory {@link XRequirement} in a resoved {@link XModule} must have a {@link XWire}</li>
 * 
 * @author thomas.diesler@jboss.com
 * @since 05-Jul-2010
 */
public class ResultProcessor {
    private FelixResolver resolver;

    ResultProcessor(FelixResolver resolver) {
        this.resolver = resolver;
    }

    public void setModuleWires(ModuleExt moduleExt, List<Wire> fwires) {
        // Set the wires on the felix module
        moduleExt.setWires(fwires);

        // Iterate over all standard requirements
        List<XWire> result = new ArrayList<XWire>();
        AbstractModule module = moduleExt.getModule();
        for (XRequirement req : module.getRequirements()) {
            AbstractModule importer = (AbstractModule) req.getModule();

            // Get the associated felix requirement
            Requirement freq = req.getAttachment(Requirement.class);
            if (freq == null)
                throw new IllegalStateException("Cannot obtain felix requirement from: " + req);

            // Find the wire that corresponds to the felix requirement
            Wire fwire = findWireForRequirement(fwires, freq);
            if (fwire == null) {
                handleNullWire(result, req);
                continue;
            }

            // Get the exporter
            Capability fcap = fwire.getCapability();
            ModuleExt fexporter = (ModuleExt) fwire.getExporter();
            XModule exporter = fexporter.getModule();

            // Find the coresponding capability
            XCapability cap = findCapability(fexporter, fcap);
            resolver.addWire(importer, req, exporter, cap);
        }
    }

    private void handleNullWire(List<XWire> result, XRequirement req) {
        XWire wire = null;

        // Felix does not maintain wires to capabilies provided by the same bundle
        if (req instanceof XPackageRequirement) {
            AbstractModule importer = (AbstractModule) req.getModule();
            XPackageCapability cap = getMatchingPackageCapability(importer, req);
            XModule exporter = null;

            if (cap == null) {
                Requirement freq = req.getAttachment(Requirement.class);
                if (importer.isFragment()) {
                    XModule host = getFragmentHost(importer);
                    ModuleExt hostExt = host.getAttachment(ModuleExt.class);
                    Wire fwire = findWireForRequirement(hostExt.getWires(), freq);
                    if (fwire != null) {
                        cap = (XPackageCapability) findCapability((ModuleExt) fwire.getExporter(), fwire.getCapability());
                    } else {
                        // If the host has the capability but nobody imports it, there is no wire yet
                        // in that case find the capability directly in the host.
                        Capability fcap = Util.getSatisfyingCapability(hostExt, freq);
                        if (fcap != null)
                            cap = (XPackageCapability) findCapability(hostExt, fcap);
                    }
                } else {
                    // If the importer is not a fragment, but the capability is provided by its fragments...
                    for (XModule frag : getFragments(importer)) {
                        ModuleExt fragExt = frag.getAttachment(ModuleExt.class);
                        Capability fcap = Util.getSatisfyingCapability(fragExt, freq);
                        if (fcap != null) {
                            cap = (XPackageCapability) findCapability(fragExt, fcap);
                            if (cap != null) {
                                // The actual wire is exported by the host, not the fragment
                                exporter = importer;
                                break;
                            }
                        }
                    }
                }
            }

            // Add the additional wire
            if (cap != null) {
                if (exporter == null)
                    exporter = cap.getModule();

                wire = resolver.addWire(importer, req, exporter, cap);
            }
        }

        // Provide a wire to the Fragment-Host bundle capability
        else if (req instanceof XFragmentHostRequirement) {
            AbstractModule fragModule = (AbstractModule) req.getModule();
            XModule hostModule = getFragmentHost(fragModule);
            XBundleCapability hostCap = hostModule.getBundleCapability();
            wire = resolver.addWire(fragModule, req, hostModule, hostCap);
        }

        if (wire == null && req.isOptional() == false)
            throw new IllegalStateException("Cannot find a wire for mandatory requirement: " + req);
    }

    private XPackageCapability getMatchingPackageCapability(XModule module, XRequirement req) {
        for (XPackageCapability cap : module.getPackageCapabilities()) {
            // Add a wire if there is a match to a capability provided by the same module
            if (((AbstractPackageRequirement) req).match(cap))
                return cap;
        }
        return null;
    }

    private XModule getFragmentHost(XModule fragModule) {
        ModuleExt ffrag = fragModule.getAttachment(ModuleExt.class);
        ModuleExt fHost = resolver.findHost(ffrag);
        XModule hostModule = fHost.getModule();
        return hostModule;
    }

    private List<XModule> getFragments(XModule hostModule) {
        ModuleExt host = hostModule.getAttachment(ModuleExt.class);
        List<XModule> fragModules = new ArrayList<XModule>();
        for (ModuleExt frag : resolver.findFragments(host))
            fragModules.add(frag.getModule());

        return fragModules;
    }

    public void setResolved(ModuleExt moduleExt) {
        moduleExt.setResolved();
        resolver.setResolved(moduleExt.getModule());
    }

    private Wire findWireForRequirement(List<Wire> fwires, Requirement freq) {
        Wire fwire = null;
        if (fwires != null) {
            for (Wire aux : fwires) {
                Requirement auxreq = aux.getRequirement();
                if (auxreq == freq) {
                    fwire = aux;
                    break;
                }

                if (auxreq instanceof FragmentRequirement) {
                    auxreq = ((FragmentRequirement) auxreq).getRequirement();
                    if (auxreq == freq) {
                        fwire = aux;
                        break;
                    }
                }
            }
        }
        return fwire;
    }

    private XCapability findCapability(ModuleExt fexporter, Capability fcap) {
        String capNamespace = fcap.getNamespace();
        Attribute capValue = fcap.getAttribute(capNamespace);

        AbstractModule exporter = fexporter.getModule();
        for (XCapability aux : exporter.getCapabilities()) {
            Capability auxfcap = aux.getAttachment(Capability.class);
            if (auxfcap == fcap)
                return aux;
        }

        // If the exporter does not define the capability
        // scan the attached fragments if there are any
        for (Module fFragment : fexporter.getFragments()) {
            exporter = ((ModuleExt) fFragment).getModule();
            for (XCapability aux : exporter.getCapabilities()) {
                Capability faux = aux.getAttachment(Capability.class);
                String auxNamespace = faux.getNamespace();
                Attribute auxValue = faux.getAttribute(auxNamespace);

                boolean match = capNamespace.equals(auxNamespace);
                match = match && (capValue != null && capValue.equals(auxValue));
                if (match == true)
                    return aux;
            }
        }
        return null;
    }
}