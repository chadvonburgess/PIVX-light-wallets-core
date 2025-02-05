package chain;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ListenableFuture;

import global.utils.Io;
import org.pivxj.core.BlockChain;
import org.pivxj.core.CheckpointManager;
import org.pivxj.core.Context;
import org.pivxj.core.Peer;
import org.pivxj.core.PeerGroup;
import org.pivxj.core.Sha256Hash;
import org.pivxj.core.StoredBlock;
import org.pivxj.core.Transaction;
import org.pivxj.core.TransactionBroadcast;
import org.pivxj.core.listeners.PeerConnectedEventListener;
import org.pivxj.core.listeners.PeerDataEventListener;
import org.pivxj.core.listeners.PeerDisconnectedEventListener;
import org.pivxj.core.listeners.PreMessageReceivedEventListener;
import org.pivxj.net.discovery.DnsDiscovery;
import org.pivxj.net.discovery.MultiplexingDiscovery;
import org.pivxj.net.discovery.PeerDiscovery;
import org.pivxj.net.discovery.PeerDiscoveryException;
import org.pivxj.params.MainNetParams;
import org.pivxj.params.RegTestParams;
import org.pivxj.params.TestNet3Params;
import org.pivxj.store.BlockStore;
import org.pivxj.store.BlockStoreException;
import org.pivxj.store.LevelDBBlockStore;
import org.pivxj.store.SPVBlockStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import global.ContextWrapper;
import global.PivtrumGlobalData;
import global.WalletConfiguration;
import pivtrum.PivtrumPeerData;
import wallet.WalletManager;


public class BlockchainManager {

    private static final Logger LOG = LoggerFactory.getLogger(BlockchainManager.class);

    public static final int BLOCKCHAIN_STATE_OFF = 10;
    public static final int BLOCKCHAIN_STATE_ON = 11;

    /** User-agent to use for network access. */
    public final String USER_AGENT;

    // system
    private ContextWrapper context;

    // wallet files..
    private WalletManager walletManager;
    private WalletConfiguration conf;

    private BlockStore blockStore;
    private File blockChainFile;
    private BlockChain blockChain;
    private PeerGroup peerGroup;

    private List<BlockchainManagerListener> blockchainManagerListeners;

    public BlockchainManager(ContextWrapper contextWrapper,WalletManager walletManager, WalletConfiguration conf) {
        this.walletManager = walletManager;
        this.conf = conf;
        this.context = contextWrapper;
        this.USER_AGENT = context.getPackageName()+"_AGENT";
        this.blockchainManagerListeners = new ArrayList<>();
    }

    public void init(BlockStore blockStoreInit,File blockStoreDir,String blockStoreFilename,boolean blockStoreFileExists){
        synchronized (this) {

            // todo: en vez de que el service este maneje el blockchain deberia crear una clase que lo haga..
            if (blockStoreFilename != null)
                blockChainFile = new File(blockStoreDir, blockStoreFilename);
            else {
                blockChainFile = blockStoreDir;
            }
            boolean blockChainFileExists = blockChainFile.exists();

            try {
                if (blockStoreInit != null && blockStoreInit.getChainHead().getHeight() < 2) {
                    blockChainFileExists = false;
                    LOG.info("resetting blockstore for been on block height: " + blockStoreInit.getChainHead().getHeight() );
                }
            } catch (BlockStoreException e) {
                e.printStackTrace();
                LOG.error("init blockstore exception",e);
            }

            if (!blockChainFileExists) {
                LOG.info("blockchain does not exist, resetting wallet. File: " + blockChainFile.getAbsolutePath());
                walletManager.reset();
            }

            // Create the blockstore
            try {
                this.blockStore = (blockStoreInit != null) ? blockStoreInit : new LevelDBBlockStore(conf.getWalletContext(), blockChainFile);
                blockStore.getChainHead(); // detect corruptions as early as possible

                final long earliestKeyCreationTime = walletManager.getEarliestKeyCreationTime();

                if (!blockChainFileExists && earliestKeyCreationTime > 0 && !(conf.getNetworkParams() instanceof RegTestParams)) {
                    try {
                        String filename = conf.getCheckpointFilename();
                        String suffix = conf.getNetworkParams() instanceof MainNetParams ? "":"-testnet";
                        final Stopwatch watch = Stopwatch.createStarted();
                        final InputStream checkpointsInputStream =  context.openAssestsStream(filename+suffix);
                        CheckpointManager.checkpoint(conf.getNetworkParams(), checkpointsInputStream, blockStore, earliestKeyCreationTime);
                        watch.stop();
                        LOG.info("checkpoints loaded from '{}', took {}", conf.getCheckpointFilename(), watch);
                    }catch (final IOException x) {
                        LOG.error("problem reading checkpoints, continuing without", x);
                    }catch (Exception e){
                        LOG.error("problem reading checkpoints, continuing without", e);
                    }
                }

            } catch (final BlockStoreException x) {
                blockChainFile.delete();

                final String msg = "blockstore cannot be created";
                LOG.error(msg, x);
                throw new Error(msg, x);
            }

            // create the blockchain
            try {
                blockChain = new BlockChain(conf.getNetworkParams(), blockStore);
                Context.get().blockChain = blockChain;
                walletManager.addWalletFrom(blockChain);
            } catch (final BlockStoreException x) {
                throw new Error("blockchain cannot be created", x);
            }

        }

    }

    public void addDiscuonnectedEventListener(PeerDisconnectedEventListener listener){
        peerGroup.addDisconnectedEventListener(listener);
    }

    public void addConnectivityListener(PeerConnectedEventListener listener){
        peerGroup.addConnectedEventListener(listener);
    }

    public void removeDisconnectedEventListener(PeerDisconnectedEventListener listener){
        if (peerGroup!=null)
            peerGroup.removeDisconnectedEventListener(listener);
    }

    public void removeConnectivityListener(PeerConnectedEventListener listener){
        if (peerGroup!=null)
            peerGroup.removeConnectedEventListener(listener);
    }

    public void addBlockchainManagerListener(BlockchainManagerListener listener){
        if (blockchainManagerListeners==null) blockchainManagerListeners = new ArrayList<>();
        blockchainManagerListeners.add(listener);
    }

    public void removeBlockchainManagerListener(BlockchainManagerListener listener){
        if (blockchainManagerListeners!=null){
            blockchainManagerListeners.remove(listener);
        }
    }

    /**
     *
     * @param transactionHash
     */
    public ListenableFuture<Transaction> broadcastTransaction(byte[] transactionHash) {
        final Sha256Hash hash = Sha256Hash.wrap(transactionHash);
        final Transaction tx = walletManager.getTransaction(hash);
        return broadcastTransaction(tx);
    }
    public ListenableFuture<Transaction> broadcastTransaction(Transaction tx){
        if (peerGroup != null && tx != null) {
            LOG.info("broadcasting transaction " + tx.getHashAsString());
            boolean onlyTrustedNode =
                    (conf.getNetworkParams() instanceof RegTestParams || conf.getNetworkParams() instanceof TestNet3Params)
                            ||
                            conf.getTrustedNodeHost()!=null;
            TransactionBroadcast transactionBroadcast = peerGroup.broadcastTransaction(
                    tx,
                    onlyTrustedNode?1:2,
                    false);
            return transactionBroadcast.broadcast();
        } else {
            LOG.info("peergroup or tx null not available, not broadcasting transaction " + ((tx != null) ? tx.getHashAsString() : ""));
            return null;
        }
    }

    private void stopPeerGroup(){
        if (peerGroup != null) {
            walletManager.removeWalletFrom(peerGroup);
            if (peerGroup.isRunning()) {
                peerGroup.stopAsync();
            }
            peerGroup = null;
            LOG.info("peergroup stopped");
        }
    }

    private void stopPeerGroupSync(){
        if (peerGroup != null) {
            walletManager.removeWalletFrom(peerGroup);
            if (peerGroup.isRunning())
                peerGroup.stop();
            peerGroup = null;
            LOG.info("peergroup stopped");
        }
    }
/*
    public void rollbackTo(int height){
        // Stop peergroup
        stopPeerGroup();

        if (blockStore != null) {
            try {
                blockStore.close();
            } catch (final BlockStoreException x) {
                throw new RuntimeException(x);
            }
        }

        // save the wallet
        walletManager.saveWallet();

        if (height > 0 && blockStore instanceof RollbackBlockStore) {
            LOG.info("Blockchain rollback");
            try {
                ((RollbackBlockStore) blockStore).rollbackTo(height);
            } catch (BlockStoreException e) {
                LoggerFactory.getLogger(BlockchainManager.class).error("Cannot rollback blockchain..");
            }
            blockChain = null;
            blockStore = null;
        }
    }*/

    public void destroy(boolean resetBlockchainOnShutdown) {
        // Stop peergroup
        stopPeerGroupSync();

        if (blockStore != null) {
            try {
                blockStore.close();
            } catch (final BlockStoreException x) {
                throw new RuntimeException(x);
            }
        }

        if (walletManager.isStarted()){
            // save the wallet
            walletManager.saveWallet();
        }


        if (resetBlockchainOnShutdown) {
            LOG.info("removing blockchain");
            //todo look at this maybe being different
//            try{
//                if (conf.getWalletContext().accStore != null)
//                    conf.getWalletContext().accStore.truncate();
//            }catch (Exception e){
//                // swallow
//            }

            try{
                if (blockStore instanceof TruncableStore){
                    ((TruncableStore) blockStore).truncate();
                }
            }catch (Exception e){
                // swallow
            }

            blockChain = null;
            blockStore = null;
            if(!blockChainFile.delete()){
                try {
                    Io.delete(blockChainFile);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public void check(Set<Impediment> impediments,
                      PeerConnectedEventListener peerConnectivityListener,
                      PeerDisconnectedEventListener peerDisconnectedEventListener ,
                      PeerDataEventListener blockchainDownloadListener,
                      PreMessageReceivedEventListener preMessageReceivedEventListener){
        synchronized (this) {

            if (impediments.isEmpty() && peerGroup == null) {

                for (BlockchainManagerListener blockchainManagerListener : blockchainManagerListeners) {
                    blockchainManagerListener.checkStart();
                }

                // consistency check
                final int walletLastBlockSeenHeight = walletManager.getLastBlockSeenHeight();
                int bestChainHeight = 0;
                if (blockChain != null)
                    bestChainHeight = blockChain.getBestChainHeight();
                Context.get().blockChain = blockChain;
                if (walletLastBlockSeenHeight != -1 && walletLastBlockSeenHeight != bestChainHeight) {
                    final String message = "wallet/blockchain out of sync: " + walletLastBlockSeenHeight + "/" + bestChainHeight;
                    LOG.error(message);
//                CrashReporter.saveBackgroundTrace(new RuntimeException(message), application.packageInfoWrapper());
                }
                LOG.info("starting peergroup");
                peerGroup = new PeerGroup(conf.getNetworkParams(), blockChain);
                peerGroup.setDownloadTxDependencies(0); // recursive implementation causes StackOverflowError
                walletManager.addWalletFrom(peerGroup);
                peerGroup.setUserAgent(USER_AGENT, context.getVersionName());

                    peerGroup.addConnectedEventListener(peerConnectivityListener);
                    peerGroup.addDisconnectedEventListener(peerDisconnectedEventListener);

                    if (preMessageReceivedEventListener != null)
                        peerGroup.addPreMessageReceivedEventListener(preMessageReceivedEventListener);

                // Memory check
                final int maxConnectedPeers = context.isMemoryLow() ? 4 : 6 ;

                final String trustedPeerHost = conf.getTrustedNodeHost();
                final int trustedPeerPort = conf.getTrustedNodePort();
                final boolean hasTrustedPeer = trustedPeerHost != null;

                final boolean connectTrustedPeerOnly = trustedPeerHost != null;//hasTrustedPeer && config.getTrustedPeerOnly();
                peerGroup.setMaxConnections(connectTrustedPeerOnly ? 1 : maxConnectedPeers);
                peerGroup.setConnectTimeoutMillis(conf.getPeerTimeoutMs());
                peerGroup.setPeerDiscoveryTimeoutMillis(conf.getPeerDiscoveryTimeoutMs());
                peerGroup.setMinBroadcastConnections(1);

                if (conf.getNetworkParams().equals(RegTestParams.get())) {
                    peerGroup.addPeerDiscovery(new PeerDiscovery() {
                        @Override
                        public InetSocketAddress[] getPeers(long services, long timeoutValue, TimeUnit timeUnit) throws PeerDiscoveryException {
                            // No regtest in pivx yet..
                            return null; //RegtestUtil.getPeersToConnect(conf.getNetworkParams(),conf.getNode());
                        }

                        @Override
                        public void shutdown() {

                        }
                    });
                } else {
                        peerGroup.addPeerDiscovery(new PeerDiscovery() {

                            private final PeerDiscovery normalPeerDiscovery = MultiplexingDiscovery.forServices(conf.getNetworkParams(), 0);

                            @Override
                            public InetSocketAddress[] getPeers(final long services, final long timeoutValue, final TimeUnit timeoutUnit)
                                    throws PeerDiscoveryException {
                                List<InetSocketAddress> peers = new LinkedList<>();

                                boolean needsTrimPeersWorkaround = false;

                                final String trustedPeerHost = conf.getTrustedNodeHost();
                                final int trustedPeerPort = conf.getTrustedNodePort();

                                boolean hasTrustedPeer = trustedPeerHost != null;

                                if (hasTrustedPeer) {
                                    LOG.info("trusted peer '" + trustedPeerHost + "'" + " only");
                                    final InetSocketAddress addr;

                                    int port;
                                    if (trustedPeerPort == 0){
                                        port = conf.getNetworkParams().getPort();
                                    }else
                                        port = trustedPeerPort;

                                    addr = new InetSocketAddress(trustedPeerHost, port);

                                    if (addr.isUnresolved()) {
                                        LOG.warn("Unresolved trusted peer, " + addr);
                                        for (PivtrumPeerData pivtrumPeerData : PivtrumGlobalData.listTrustedHosts(conf.getNetworkParams() , conf.getNetworkParams().getPort())) {
                                            InetSocketAddress socketAddress = new InetSocketAddress(pivtrumPeerData.getHost(), pivtrumPeerData.getTcpPort());
                                            if (!socketAddress.isUnresolved()) {
                                                peers.add(socketAddress);
                                            }else {
                                                LOG.warn("Unresolved peer, " + socketAddress);
                                            }
                                        }
                                        // Remove already connected peers
                                        peers = removeAlreadyConnectedPeers(peers);

                                        InetSocketAddress[] nodes = new InetSocketAddress[peers.size()];
                                        for (int i = 0; i < peers.size(); i++) {
                                            nodes[i] = peers.get(i);
                                        }
                                        return nodes;
                                    }


                                    if (addr.getAddress() != null) {
                                        peers.add(addr);
                                        needsTrimPeersWorkaround = true;
                                    }
                                }else {
                                    for (PivtrumPeerData pivtrumPeerData : PivtrumGlobalData.listTrustedHosts(conf.getNetworkParams(), conf.getNetworkParams().getPort())) {
                                        InetSocketAddress socketAddress = new InetSocketAddress(pivtrumPeerData.getHost(), pivtrumPeerData.getTcpPort());
                                        if (!socketAddress.isUnresolved()) {
                                            peers.add(socketAddress);
                                        }else {
                                            LOG.warn("Unresolved peer, " + socketAddress);
                                        }
                                    }
                                    // remove already connected peers
                                    peers = removeAlreadyConnectedPeers(peers);
                                    InetSocketAddress[] nodes = new InetSocketAddress[peers.size()];
                                    for (int i = 0; i < peers.size(); i++) {
                                        nodes[i] = peers.get(i);
                                    }
                                    return nodes;
                                }

                                if (!hasTrustedPeer && peers.isEmpty())
                                    peers.addAll(Arrays.asList(normalPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)));

                                // workaround because PeerGroup will shuffle peers
                                if (needsTrimPeersWorkaround)
                                    while (peers.size() >= maxConnectedPeers)
                                        peers.remove(peers.size() - 1);
                                // Remove already connected peers
                                peers = removeAlreadyConnectedPeers(peers);
                                return peers.toArray(new InetSocketAddress[0]);
                            }

                            @Override
                            public void shutdown() {
                                normalPeerDiscovery.shutdown();
                            }
                        });
                }

                // notify that the peergroup was initialized
                if (blockchainManagerListeners != null) {
                    for (BlockchainManagerListener blockchainManagerListener : blockchainManagerListeners) {
                        blockchainManagerListener.peerGroupInitialized(peerGroup);
                    }
                }

                // init peergroup
                //peerGroup.addBlocksDownloadedEventListener(blockchainDownloadListener);
                peerGroup.startAsync();
                peerGroup.startBlockChainDownload(blockchainDownloadListener);

            } else if (!impediments.isEmpty() && peerGroup != null) {
                LOG.info("stopping peergroup");
                peerGroup.removeDisconnectedEventListener(peerDisconnectedEventListener);
                peerGroup.removeConnectedEventListener(peerConnectivityListener);
                walletManager.removeWalletFrom(peerGroup);
                peerGroup.stopAsync();
                peerGroup = null;

                for (BlockchainManagerListener blockchainManagerListener : blockchainManagerListeners) {
                    blockchainManagerListener.checkEnd();
                }

                notifyBlockchainStateOff(impediments);
            }
        }

        //todo: falta hacer el tema de la memoria, hoy en día si se queda sin memoria no dice nada..
        //todo: ver si conviene esto..
//        broadcastBlockchainState();

    }

    private List<InetSocketAddress> removeAlreadyConnectedPeers(List<InetSocketAddress> peers) {
        // Remove the already connected peers
        for (Peer peer : peerGroup.getConnectedPeers()) {
            for (int i = 0; i < peers.size(); i++) {
                InetSocketAddress inetSocketAddress = peers.get(i);
                if (inetSocketAddress.getHostName().equals(peer.getAddress().getHostname())) {
                    if (inetSocketAddress.getPort() == peer.getAddress().getPort()){
                        peers.remove(inetSocketAddress);
                        break;
                    }
                }
            }
        }
        return peers;
    }

    private void notifyBlockchainStateOff(Set<Impediment> impediments) {
        for (BlockchainManagerListener blockchainManagerListener : blockchainManagerListeners) {
            blockchainManagerListener.onBlockchainOff(impediments);
        }
    }


    /*public BlockchainState getBlockchainState(Set<Impediment> impediments)
    {
        final StoredBlock chainHead = blockChain.getChainHead();
        final Date bestChainDate = chainHead.getHeader().getTime();
        final int bestChainHeight = chainHead.getHeight();
        final boolean replaying = chainHead.getHeight() < conf.getBestChainHeightEver();

        return new BlockchainState(bestChainDate, bestChainHeight, replaying, impediments);
    }*/


    public List<Peer> getConnectedPeers() {
        if (peerGroup != null)
            return peerGroup.getConnectedPeers();
        else
            return null;
    }


    public List<StoredBlock> getRecentBlocks(final int maxBlocks) {
        final List<StoredBlock> blocks = new ArrayList<StoredBlock>(maxBlocks);
        try{
            StoredBlock block = blockChain.getChainHead();
            while (block != null) {
                blocks.add(block);
                if (blocks.size() >= maxBlocks)
                    break;
                block = block.getPrev(blockStore);
            }
        }
        catch (final BlockStoreException x) {
            // swallow
        }
        return blocks;
    }

    public PeerGroup getPeerGroup(){
        return peerGroup;
    }

    public int getChainHeadHeight() {
        return blockChain != null ? blockChain.getChainHead().getHeight() : 0;
    }


    public void removeBlockchainDownloadListener(PeerDataEventListener blockchainDownloadListener) {
        if (peerGroup != null)
            peerGroup.removeBlocksDownloadedEventListener(blockchainDownloadListener);
    }

    public List<Peer> listConnectedPeers() {
        if (peerGroup!=null)
            return peerGroup.getConnectedPeers();
        return new ArrayList<>();
    }

    public int getProtocolVersion() {
        return conf.getProtocolVersion();
    }
}