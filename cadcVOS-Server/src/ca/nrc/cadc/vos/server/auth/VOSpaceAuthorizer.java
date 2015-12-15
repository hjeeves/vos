/**
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2010.                            (c) 2010.
 *  Government of Canada                 Gouvernement du Canada
 *  National Research Council            Conseil national de recherches
 *  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
 *  All rights reserved                  Tous droits réservés
 *
 *  NRC disclaims any warranties,        Le CNRC dénie toute garantie
 *  expressed, implied, or               énoncée, implicite ou légale,
 *  statutory, of any kind with          de quelque nature que ce
 *  respect to the software,             soit, concernant le logiciel,
 *  including without limitation         y compris sans restriction
 *  any warranty of merchantability      toute garantie de valeur
 *  or fitness for a particular          marchande ou de pertinence
 *  purpose. NRC shall not be            pour un usage particulier.
 *  liable in any event for any          Le CNRC ne pourra en aucun cas
 *  damages, whether direct or           être tenu responsable de tout
 *  indirect, special or general,        dommage, direct ou indirect,
 *  consequential or incidental,         particulier ou général,
 *  arising from the use of the          accessoire ou fortuit, résultant
 *  software.  Neither the name          de l'utilisation du logiciel. Ni
 *  of the National Research             le nom du Conseil National de
 *  Council of Canada nor the            Recherches du Canada ni les noms
 *  names of its contributors may        de ses  participants ne peuvent
 *  be used to endorse or promote        être utilisés pour approuver ou
 *  products derived from this           promouvoir les produits dérivés
 *  software without specific prior      de ce logiciel sans autorisation
 *  written permission.                  préalable et particulière
 *                                       par écrit.
 *
 *  This file is part of the             Ce fichier fait partie du projet
 *  OpenCADC project.                    OpenCADC.
 *
 *  OpenCADC is free software:           OpenCADC est un logiciel libre ;
 *  you can redistribute it and/or       vous pouvez le redistribuer ou le
 *  modify it under the terms of         modifier suivant les termes de
 *  the GNU Affero General Public        la “GNU Affero General Public
 *  License as published by the          License” telle que publiée
 *  Free Software Foundation,            par la Free Software Foundation
 *  either version 3 of the              : soit la version 3 de cette
 *  License, or (at your option)         licence, soit (à votre gré)
 *  any later version.                   toute version ultérieure.
 *
 *  OpenCADC is distributed in the       OpenCADC est distribué
 *  hope that it will be useful,         dans l’espoir qu’il vous
 *  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
 *  without even the implied             GARANTIE : sans même la garantie
 *  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
 *  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
 *  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
 *  General Public License for           Générale Publique GNU Affero
 *  more details.                        pour plus de détails.
 *
 *  You should have received             Vous devriez avoir reçu une
 *  a copy of the GNU Affero             copie de la Licence Générale
 *  General Public License along         Publique GNU Affero avec
 *  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
 *  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
 *                                       <http://www.gnu.org/licenses/>.
 *
 ************************************************************************
 */
package ca.nrc.cadc.vos.server.auth;

import ca.nrc.cadc.ac.Role;
import ca.nrc.cadc.ac.UserNotFoundException;
import ca.nrc.cadc.ac.client.GMSClient;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.AccessControlContext;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.Principal;
import java.security.cert.CertificateException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.x500.X500Principal;

import org.apache.log4j.Logger;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.Authorizer;
import ca.nrc.cadc.auth.DelegationToken;
import ca.nrc.cadc.auth.SSLUtil;
import ca.nrc.cadc.auth.X509CertificateChain;
import ca.nrc.cadc.cred.client.CredClient;
import ca.nrc.cadc.net.TransientException;
import ca.nrc.cadc.profiler.Profiler;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.vos.ContainerNode;
import ca.nrc.cadc.vos.Node;
import ca.nrc.cadc.vos.NodeLockedException;
import ca.nrc.cadc.vos.NodeNotFoundException;
import ca.nrc.cadc.vos.NodeProperty;
import ca.nrc.cadc.vos.VOS;
import ca.nrc.cadc.vos.VOSURI;
import ca.nrc.cadc.vos.server.NodeID;
import ca.nrc.cadc.vos.server.NodePersistence;
import java.io.File;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;


/**
 * Authorization implementation for VO Space.
 *
 * Important:  This class cannot be re-used between HTTP Requests--A new
 * instance must be created each time.
 *
 * The nodePersistence object must be set for every instance.
 *
 * @author majorb
 */
public class VOSpaceAuthorizer implements Authorizer
{
    protected static final Logger LOG = Logger.getLogger(VOSpaceAuthorizer.class);

    // TODO: make these configurable or find from the VOSpace capabilities
    private static final String CRED_SERVICE_ID = "ivo://cadc.nrc.ca/cred";
    private static final String CANFAR_GMS_SERVICE_ID = "ivo://cadc.nrc.ca/canfargms";

    public static final String MODE_KEY = VOSpaceAuthorizer.class.getName() + ".state";
    public static final String OFFLINE = "Offline";
    public static final String OFFLINE_MSG = "System is offline for maintainence";
    public static final String READ_ONLY = "ReadOnly";
    public static final String READ_ONLY_MSG = "System is in read-only mode for maintainence";
    public static final String READ_WRITE = "ReadWrite";
    private boolean readable = true;
    private boolean writable  = true;
    private boolean allowPartialPaths = false;
    private boolean disregardLocks = false;

    private NodePersistence nodePersistence;
    private RegistryClient registryClient;
    private GMSClient canfarGMS;
    
    private final Profiler profiler = new Profiler(VOSpaceAuthorizer.class);

    public VOSpaceAuthorizer()
    {
        this(false);
    }

    public VOSpaceAuthorizer(boolean allowPartialPaths)
    {
        initState();
        this.allowPartialPaths = allowPartialPaths;

        try
        {
            this.registryClient = new RegistryClient();
            
            URL url = registryClient.getServiceURL(URI.create(CANFAR_GMS_SERVICE_ID), VOS.GMS_PROTOCOL);
            LOG.debug(CANFAR_GMS_SERVICE_ID + " -> " + url);
            this.canfarGMS = new GMSClient(url.toExternalForm());
        }
        catch (IllegalArgumentException e)
        {
            throw new RuntimeException("BUG: Error creating GMS Client", e);
        } 
        catch (MalformedURLException e) 
        {
            throw new RuntimeException("BUG: Error creating GMS Client", e);
        } 
    }

    // this method will only downgrade the state to !readable and !writable
    // and will never restore them to true - that is intentional
    private void initState()
    {
        String key = VOSpaceAuthorizer.MODE_KEY;
        String val = System.getProperty(key);
        if (OFFLINE.equals(val))
        {
            readable = false;
            writable = false;
        }
        else if (READ_ONLY.equals(val))
        {
            writable = false;
        }
    }

    /**
     * Obtain the Read Permission for the given URI.
     *
     * @param uri       The URI to check.
     * @return          The persistent version of the target node.
     * @throws AccessControlException If the user does not have read permission
     * @throws FileNotFoundException If the node could not be found
     * @throws TransientException
     */
    @Override
    public Object getReadPermission(URI uri)
        throws AccessControlException, FileNotFoundException, TransientException
    {
        initState();
        if (!readable)
        {
            if (!writable)
                throw new IllegalStateException(OFFLINE_MSG);
            throw new IllegalStateException(READ_ONLY_MSG);
        }
        try
        {
            
            VOSURI vos = new VOSURI(uri);
            Node node = nodePersistence.get(vos, allowPartialPaths);
            profiler.checkpoint("nodePersistence.get");
            return getReadPermission(node);
        }
        catch(NodeNotFoundException ex)
        {
            throw new FileNotFoundException("not found: " + uri);
        }
    }

    /**
     * Obtain the Read Permission for the given Node.
     *
     * @param node      The Node to check.
     * @return          The persistent version of the target node.
     * @throws AccessControlException If the user does not have read permission
     */
    public Object getReadPermission(Node node)
            throws AccessControlException
    {
        initState();
        if (!readable)
        {
            if (!writable)
                throw new IllegalStateException(OFFLINE_MSG);
            throw new IllegalStateException(READ_ONLY_MSG);
        }

        AccessControlContext acContext = AccessController.getContext();
        Subject subject = Subject.getSubject(acContext);
        
        checkDelegation(node, subject);

        LinkedList<Node> nodes = Node.getNodeList(node);

        // check for root ownership
        Node rootNode = nodes.getLast();
        if (isOwner(rootNode, subject))
        {
            LOG.debug("Read permission granted to root user.");
            return node;
        }

        Iterator<Node> iter = nodes.descendingIterator(); // root at end
        while (iter.hasNext())
        {
            Node n = iter.next();
            if (!hasSingleNodeReadPermission(n, subject))
                throw new AccessControlException("Read permission denied on " + n.getUri().toString());
        }
        return node;
    }

    /**
     * Obtain the Write Permission for the given URI.
     *
     * @param uri       The URI to check.
     * @return          The persistent version of the target node.
     * @throws AccessControlException If the user does not have write permission
     * @throws FileNotFoundException If the node could not be found
     * @throws NodeLockedException    If the node is locked
     * @throws TransientException
     */
    @Override
    public Object getWritePermission(URI uri)
        throws AccessControlException, FileNotFoundException,
            TransientException, NodeLockedException
    {
        initState();
        if (!writable)
        {
            if (readable)
                throw new IllegalStateException(READ_ONLY_MSG);
            throw new IllegalStateException(OFFLINE_MSG);
        }

        try
        {
            VOSURI vos = new VOSURI(uri);
            Node node = nodePersistence.get(vos, allowPartialPaths);
            profiler.checkpoint("nodePersistence.get");
            return getWritePermission(node);
        }
        catch(NodeNotFoundException ex)
        {
            throw new FileNotFoundException("not found: " + uri);
        }
    }

    /**
     * Obtain the Write Permission for the given Node.
     *
     * @param node      The Node to check.
     * @return          The persistent version of the target node.
     * @throws AccessControlException If the user does not have write permission
     * @throws NodeLockedException    If the node is locked
     */
    public Object getWritePermission(Node node)
        throws AccessControlException, NodeLockedException
    {
        initState();
        if (!writable)
        {
            if (readable)
                throw new IllegalStateException(READ_ONLY_MSG);
            throw new IllegalStateException(OFFLINE_MSG);
        }

        AccessControlContext acContext = AccessController.getContext();
        Subject subject = Subject.getSubject(acContext);
        
        checkDelegation(node, subject);

        // check if the node is locked
        if (!disregardLocks && node.isLocked())
            throw new NodeLockedException(node.getUri().toString());

        // check for root ownership
        LinkedList<Node> nodes = Node.getNodeList(node);
        Node rootNode = nodes.getLast();
        if (isOwner(rootNode, subject))
        {
            LOG.debug("Write permission granted to root user.");
            return node;
        }

        Iterator<Node> iter = nodes.descendingIterator(); // root at end
        while (iter.hasNext())
        {
            Node n = iter.next();
            if (n == node) // target needs write
                if (!hasSingleNodeWritePermission(n, subject))
                    throw new AccessControlException("Write permission denied on " + n.getUri().toString());
            else // part of path needs read
                if (!hasSingleNodeReadPermission(n, subject))
                    throw new AccessControlException("Read permission denied on " + n.getUri().toString());
        }
        return node;
    }

    /**
     * Recursively checks if a node can be deleted by the current subject.
     * The caller must have write permission on the parent container and all
     * non-empty containers. The argument and all child nodes must not
     * be locked (unless locks are being ignored).
     *
     * @param node
     * @throws AccessControlException
     * @throws NodeLockedException
     * @throws TransientException
     */
    public void getDeletePermission(Node node)
        throws AccessControlException, NodeLockedException, TransientException
    {
        initState();
        if (!writable)
        {
            if (readable)
                throw new IllegalStateException(READ_ONLY_MSG);
            throw new IllegalStateException(OFFLINE_MSG);
        }

        // check parent
        ContainerNode parent = node.getParent();
        getWritePermission(parent); // checks lock and rw permission

        // check if the node is locked
        if (!disregardLocks && node.isLocked())
            throw new NodeLockedException(node.getUri().toString());

        if (node instanceof ContainerNode)
        {
            ContainerNode container = (ContainerNode) node;
            getWritePermission(container); // checks lock and rw permission

            Integer batchSize = new Integer(1000); // TODO: any value in not hard-coding this?
            VOSURI startURI = null;
            nodePersistence.getChildren(container, startURI, batchSize);
            while ( !container.getNodes().isEmpty() )
            {
                for (Node child : container.getNodes())
                {
                    getDeletePermission(child); // recursive
                    startURI = child.getUri();
                }
                // clear the children for garbage collection
                container.getNodes().clear();

                // get next batch
                nodePersistence.getChildren(container, startURI, batchSize);
                if ( !container.getNodes().isEmpty() )
                {
                    Node n = container.getNodes().get(0);
                    if ( n.getUri().equals(startURI) )
                        container.getNodes().remove(0); // avoid recheck and infinite loop
                }
            }
        }
    }

    /**
     * Given the groupURI, determine if the user identified by the subject
     * has membership.
     * @param groupURI The group or list of groups
     * @param subject The user's subject
     * @return True if the user is a member
     */
    private boolean hasMembership(NodeProperty groupProp, Subject subject)
    {
        if (subject.getPrincipals().isEmpty())
            return false;
        
        List<String> groupURIs = groupProp.extractPropertyValueList();
        if (groupURIs == null || groupURIs.isEmpty())
            return false;

        Exception firstFail = null;
        RuntimeException wrapException = null;
        try
        {
            // need credentials in the subject to call GMS
            checkCredentials(subject); // succeed or throw

            // make gms calls to see if the user has group membership
            for (String groupURI : groupURIs)
            {
                try
                {
                    LOG.debug("Checking GMS on groupURI: " + groupURI);
                    URI guri = new URI(groupURI);
                    if (guri.getFragment() != null)
                    {
                        // TODO: we should be using the base group URI to decide which GMS service to
                        // call, but CANFAR and CADC groups both have the same authority/base... doh!

                        // call CANFAR GMS
                        boolean isMember = canfarGMS.isMember(guri.getFragment(), Role.MEMBER);
                        profiler.checkpoint("canfarGMS.ismember");
                        if (isMember)
                            return true;
                    }
                    else
                        LOG.warn("skipping invalid group URI (missing fragment): " + groupURI);
                }
                catch (URISyntaxException e)
                {
                    LOG.warn("skipping invalid group URI: " + groupURI);
                }
                catch(UserNotFoundException ex)
                {
                    LOG.debug("failed to call canfar gms service", ex);
                    if (firstFail == null)
                    {
                        firstFail = ex;
                        wrapException = new AccessControlException("failed to check membership with group service: " + ex);
                    }
                }
                catch(IOException ex)
                {
                    LOG.debug("failed to call canfar gms service", ex);
                    if (firstFail == null)
                    {
                        firstFail = ex;
                        wrapException = new RuntimeException("failed to check membership with group service", ex);
                    }
                }
            }
        }
        finally { }
        
        if (wrapException != null)
            throw wrapException;
        
        return false;
    }
    
    // HACK: need to create a privaledged subject for use with CredClient calls
    // but this needs to be configurable somehow... 
    // for now: compatibility with current system config
    private Subject createOpsSubject()
    {
        File pemFile = new File(System.getProperty("user.home") + "/.pub/proxy.pem");
        return SSLUtil.createSubject(pemFile);
    }
    
    private void checkCredentials(final Subject subject)
    {
        // lazy init of credentials
        try
        {
            X509CertificateChain privateKeyChain = X509CertificateChain.findPrivateKeyChain(
                    subject.getPublicCredentials());

            // If we don't have the private key chain, get it from the credential
            // delegation service and add it to the subject's public credentials
            // for use later.
            if (privateKeyChain == null)
            {
                final CredClient cred = new CredClient(URI.create(CRED_SERVICE_ID));
                Subject opsSubject = createOpsSubject();
                try
                {
                    privateKeyChain = Subject.doAs(opsSubject, new PrivilegedExceptionAction<X509CertificateChain>()
                    {
                        public X509CertificateChain run() throws Exception
                        {
                            return cred.getProxyCertificate(subject, 0.2);
                        }
                    });
                }
                catch(PrivilegedActionException ex)
                {
                    throw new RuntimeException("CredClient.getProxyCertficate failed", ex.getException());
                }
                profiler.checkpoint("CredClient.getProxyCertificate");
                if (privateKeyChain == null)
                {
                    throw new AccessControlException("credential service did not return a delegated certificate");
                }

                privateKeyChain.getChain()[0].checkValidity();

                subject.getPublicCredentials().add(privateKeyChain);
            }
        }
        catch (AccessControlException e)
        {
            throw new RuntimeException("CredClient.getProxyCertficate failed", e);
        }
        catch (CertificateException e)
        {
            throw new AccessControlException("credential service returned an invalid certificate");
        }
    }

    /**
     * Check if the specified subject is the owner of the node.
     *
     * @param subject
     * @param node
     * @return true of the current subject is the owner, otherwise false
     */
    private boolean isOwner(Node node, Subject subject)
    {
        NodeID nodeID = (NodeID) node.appData;
        if (nodeID == null)
        {
            throw new IllegalStateException("BUG: no owner found for node: " + node);
        }
        if (nodeID.getID() == null) // root node, no owner
            return false;

        Subject owner = nodeID.getOwner();
        if (owner == null)
        {
            throw new IllegalStateException("BUG: no owner found for node: " + node);
        }

        Set<Principal> ownerPrincipals = owner.getPrincipals();
        Set<Principal> callerPrincipals = subject.getPrincipals();

        for (Principal oPrin : ownerPrincipals)
        {
            for (Principal cPrin : callerPrincipals)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug(String.format(
                            "Checking owner of node \"%s\" (owner=\"%s\") where user=\"%s\"",
                            node.getName(), oPrin, cPrin));
                }
                if (AuthenticationUtil.equals(oPrin, cPrin))
                    return true; // caller===owner
            }
        }
        return false;
    }

    /**
     * Check the read permission on a single node.
     *
     * For full node authorization, use getReadPermission(Node).
     *
     * @param node The node to check.
     * @throws AccessControlException If permission is denied.
     */
    private boolean hasSingleNodeReadPermission(Node node, Subject subject)
    {
        LOG.debug("checkSingleNodeReadPermission: " + node.getUri());
        if (node.isPublic())
            return true; // OK

        // return true if this is the owner of the node or if a member
        // of the groupRead or groupWrite property
        if (subject != null)
        {
            if (isOwner(node, subject))
            {
                LOG.debug("Node owner granted read permission.");
                return true; // OK
            }

            // the GROUPREAD property means the user has read-only permission
            NodeProperty groupRead = node.findProperty(VOS.PROPERTY_URI_GROUPREAD);
            if (LOG.isDebugEnabled())
            {
                String groupReadString = groupRead == null ? "null" : groupRead.getPropertyValue();
                LOG.debug(String.format(
                        "Checking group read permission on node \"%s\" (groupRead=\"%s\")",
                        node.getName(), groupReadString));
            }
            if (groupRead != null && groupRead.getPropertyValue() != null)
            {
                if (hasMembership(groupRead, subject))
                    return true; // OK
            }

            // the GROUPWRITE property means the user has read+write permission
            // so check that too
            NodeProperty groupWrite = node.findProperty(VOS.PROPERTY_URI_GROUPWRITE);
            if (LOG.isDebugEnabled())
            {
                String groupReadString = groupWrite == null ? "null" : groupWrite.getPropertyValue();
                LOG.debug(String.format(
                        "Checking group write permission on node \"%s\" (groupWrite=\"%s\")",
                        node.getName(), groupReadString));
            }
            if (groupWrite != null && groupWrite.getPropertyValue() != null)
            {
                if (hasMembership(groupWrite, subject))
                    return true; // OK
            }
        }

        return false;
    }

    /**
     * Check the write permission on a single node.
     *
     * For full node authorization, use getWritePermission(Node).
     *
     * @param node The node to check.
     * @throws AccessControlException If permission is denied.
     */
    private boolean hasSingleNodeWritePermission(Node node, Subject subject)
    {
        if (node.getUri().isRoot())
            return false;
        if (subject != null)
        {
            if (isOwner(node, subject))
            {
                LOG.debug("Node owner granted write permission.");
                return true; // OK
            }

            NodeProperty groupWrite = node.findProperty(VOS.PROPERTY_URI_GROUPWRITE);
            if (LOG.isDebugEnabled())
            {
                String groupReadString = groupWrite == null ? "null" : groupWrite.getPropertyValue();
                LOG.debug(String.format(
                        "Checking group write permission on node \"%s\" (groupWrite=\"%s\")",
                        node.getName(), groupReadString));
            }
            if (groupWrite != null && groupWrite.getPropertyValue() != null)
            {
                if (hasMembership(groupWrite, subject))
                    return true; // OK
            }
        }
        return false;
    }

    public void setDisregardLocks(boolean disregardLocks)
    {
        this.disregardLocks = disregardLocks;
    }

    /**
     * Node NodePersistence Getter.
     *
     * @return  NodePersistence instance.
     */
    public NodePersistence getNodePersistence()
    {
        return nodePersistence;
    }

    /**
     * Node NodePersistence Setter.
     *
     * @param nodePersistence       NodePersistence instance.
     */
    public void setNodePersistence(final NodePersistence nodePersistence)
    {
        this.nodePersistence = nodePersistence;
    }
    
    /** check for delegation cookie and, if present, does an authorization
     * against it.
     * @param node - node authorization is performed against
     * @param subject - user
     * @throws AccessControlException - unauthorized access
     */
    private void checkDelegation(Node node, Subject subject) throws AccessControlException
    {        
        Set<DelegationToken> tokens = subject.getPublicCredentials(DelegationToken.class);
        for (DelegationToken token : tokens)
        {
            VOSURI scope = new VOSURI(token.getScope());
            VOSURI tmp = node.getUri();
            while (tmp != null)
            {
                if (scope.equals(tmp))
                {
                    return;
                }
                tmp = tmp.getParentURI();
            }
            String msg = "Scoped search (" + scope + ") on node (" + 
                    node.getUri() + ")- accessed denied";
            LOG.debug(msg);
            throw new AccessControlException(msg);
        }
    }

}