package global;

import org.pivxj.core.Address;
import org.pivxj.core.Coin;
import org.pivxj.core.Context;
import org.pivxj.core.InsufficientMoneyException;
import org.pivxj.core.Peer;
import org.pivxj.core.PeerGroup;
import org.pivxj.core.ScriptException;
import org.pivxj.core.Sha256Hash;
import org.pivxj.core.Transaction;
import org.pivxj.core.TransactionInput;
import org.pivxj.core.TransactionOutPoint;
import org.pivxj.core.TransactionOutput;
import org.pivxj.core.listeners.TransactionConfidenceEventListener;
import org.pivxj.crypto.DeterministicKey;
import org.pivxj.crypto.KeyCrypter;
import org.pivxj.crypto.MnemonicException;
import org.pivxj.script.Script;
import org.pivxj.wallet.DeterministicKeyChain;
import org.pivxj.wallet.SendRequest;
import org.pivxj.wallet.UnreadableWalletException;
import org.pivxj.wallet.Wallet;
import org.pivxj.wallet.exceptions.RequestFailedErrorcodeException;
import org.pivxj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import chain.BlockchainManager;
import global.exceptions.UpgradeException;
import global.store.ContactsStoreDao;
import global.store.RateDbDao;
import global.pivx.DefaultCoinSelector;
import global.wrappers.InputWrapper;
import global.wrappers.TransactionWrapper;
import global.exceptions.CantSweepBalanceException;
import global.exceptions.ContactAlreadyExistException;
import global.exceptions.NoPeerConnectedException;
import org.spongycastle.crypto.params.KeyParameter;
import org.spongycastle.util.encoders.Base64;
import wallet.exceptions.CantRestoreEncryptedWallet;

import wallet.exceptions.InsufficientInputsException;
import wallet.exceptions.TxNotFoundException;
import wallet.WalletManager;

/**
 * Created by mati on 18/04/17.
 */

public class N8VModuleImp implements N8VModule {

    private static final Logger logger = LoggerFactory.getLogger(N8VModuleImp.class);

    private ContextWrapper context;
    private WalletConfiguration walletConfiguration;
    private WalletManager walletManager;
    private BlockchainManager blockchainManager;
    private ContactsStoreDao contactsStore;
    private RateDbDao rateDb;

    // cached balance --> todo: check this..
    private long availableBalance = 0;
    private BigDecimal pivInUsdHardcoded = new BigDecimal("1.5");

    // OS dependent helper.
    private BackupHelper backupHelper;

    public N8VModuleImp(ContextWrapper contextWrapper, WalletConfiguration walletConfiguration, ContactsStoreDao contactsStore, RateDbDao rateDb, BackupHelper backupHelper) {
        this.context = contextWrapper;
        this.walletConfiguration = walletConfiguration;
        this.contactsStore = contactsStore;
        this.rateDb = rateDb;
        this.backupHelper = backupHelper;
        walletManager = new WalletManager(contextWrapper,walletConfiguration);
        blockchainManager = new BlockchainManager(context,walletManager,walletConfiguration);
    }

    public void start() throws IOException{
        if (walletManager.isStarted() || walletManager.isStarting()) throw new IllegalStateException("Core is starting or started");
        walletManager.init();
    }

    @Override
    public void createWallet() {

    }
    @Override
    public boolean backupWallet(File backupFile, String password) throws IOException {
        return walletManager.backupWallet(backupFile,password);
    }

    private boolean backupWallet(Wallet wallet,File backupFile, String password) throws IOException {
        return walletManager.backupWallet(wallet,backupFile,password);
    }

    @Override
    public void restoreWallet(File backupFile) throws IOException {
        // restore wallet and launch the restart of the blockchain...
        walletManager.restoreWalletFromProtobuf(backupFile);
    }

    @Override
    public void restoreWalletFromEncrypted(File file, String password) throws CantRestoreEncryptedWallet, IOException {
        walletManager.restoreWalletFromEncrypted(file,password);
//
//        try {
//            int version = (forceRestoreVersion != -1) ? forceRestoreVersion : context.getCurrentVersionNumber();
//            try {
//                String filename = file.getName();
//                // try to determine the version from the file's name
//                version = Integer.parseInt(filename.substring(0, 1));
//            } catch (Exception e) {
//                // swallow
//            }
//            walletManager.restoreWalletFromEncrypted(file, password, version);
//        } catch (UnreadableWalletException e) {
//            logger.warn("Cannot restore wallet, UnreadableWalletException", e);
//            throw new CantRestoreEncryptedWallet(e);
//        }
    }

    @Override
    public void restoreWallet(List<String> mnemonic, long timestamp,boolean bip44) throws IOException, MnemonicException {
        walletManager.restoreWalletFrom(mnemonic,timestamp,bip44);
    }

    @Override
    public boolean isWalletCreated() {
        return false;
    }

    @Override
    public Address getReceiveAddress() {
        return walletManager.getCurrentAddress();
    }

    @Override
    public Address getFreshNewAddress(){
        return walletManager.newFreshReceiveAddress();
    }

    @Override
    public boolean isAddressUsed(Address address) {
        return walletManager.isAddressMine(address);
    }

    @Override
    public long getAvailableBalance() {
        return walletManager.getAvailableBalance().longValue();
    }

    @Override
    public Coin getAvailableBalanceCoin() {
        return walletManager.getAvailableBalance();
    }

    @Override
    public Coin getUnnavailableBalanceCoin() {
        return walletManager.getUnspensableBalance();
    }

    @Override
    public boolean isWalletWatchOnly(){
        return walletManager.isWatchOnly();
    }

    @Override
    public BigDecimal getAvailableBalanceLocale() {
        return pivInUsdHardcoded.multiply(new BigDecimal(availableBalance));
    }

    @Override
    public List<AddressLabel> getContacts(){
        return contactsStore.getContacts();
    }

    @Override
    public AddressLabel getAddressLabel(String address) {
        return contactsStore.getContact(address);
    }

    @Override
    public List<AddressLabel> getMyAddresses() {
        return contactsStore.getMyAddresses();
    }

    @Override
    public void saveContact(AddressLabel addressLabel) throws ContactAlreadyExistException {
        if (contactsStore.getContact(addressLabel.getAddresses().get(0))!=null) throw new ContactAlreadyExistException();
        contactsStore.insert(addressLabel);
    }
    @Override
    public void saveContactIfNotExist(AddressLabel addressLabel){
        if (contactsStore.getContact(addressLabel.getAddresses().get(0))!=null) return;
        contactsStore.insert(addressLabel);
    }

    @Override
    public void deleteAddressLabel(AddressLabel data) {
        contactsStore.delete(data);
    }

    @Override
    public boolean chechAddress(String addressBase58) {
        boolean result = false;
        try {
            Address.fromBase58(walletConfiguration.getNetworkParams(), addressBase58);
            result = true;
        }catch (Exception e){
            // nothing..
        }
        return result;
    }

    @Override
    public Transaction buildSendTx(String addressBase58, Coin amount, String memo,Address changeAddress) throws InsufficientMoneyException {
        return buildSendTx(addressBase58,amount,null,memo,changeAddress);
    }
    @Override
    public Transaction buildSendTx(String addressBase58, Coin amount,Coin feePerKb, String memo,Address changeAddress) throws InsufficientMoneyException{
        Address address = Address.fromBase58(walletConfiguration.getNetworkParams(), addressBase58);

        SendRequest sendRequest = SendRequest.to(address,amount);
        sendRequest.memo = memo;
        sendRequest.signInputs = true;
        sendRequest.shuffleOutputs = false; // don't shuffle outputs to know the contact
        if (feePerKb!=null)
            sendRequest.feePerKb = feePerKb;
        if (changeAddress!=null){
            sendRequest.changeAddress = changeAddress;
        }
        walletManager.completeSend(sendRequest);

        return sendRequest.tx;
    }

    @Override
    public Transaction completeTx(Transaction transaction,Address changeAddress,Coin feePerKb) throws InsufficientMoneyException {
        SendRequest sendRequest = SendRequest.forTx(transaction);
        if (transaction.getInputs()!=null && !transaction.getInputs().isEmpty()){
            List<TransactionOutput> unspent = new ArrayList<>();
            for (TransactionInput input : transaction.getInputs()) {
                unspent.add(input.getConnectedOutput());
            }
            sendRequest.coinSelector = new DefaultCoinSelector(unspent);
        }
        sendRequest.signInputs = true;
        sendRequest.shuffleOutputs = false; // don't shuffle outputs to know the contact
        if (changeAddress!=null){
            sendRequest.changeAddress = changeAddress;
        }
        if (feePerKb!=null)
            sendRequest.feePerKb = feePerKb;
        //sendRequest.changeAddress -> add the change address with address that i know instead of give this job to the wallet.
        walletManager.completeSend(sendRequest);

        return sendRequest.tx;
    }

    public Transaction completeTx(Transaction transaction) throws InsufficientMoneyException{
        SendRequest sendRequest = SendRequest.forTx(transaction);
        sendRequest.changeAddress = null;
        sendRequest.signInputs = true;
        sendRequest.shuffleOutputs = false;
        walletManager.getWallet().completeTx(sendRequest);
        //walletManager.getWallet().signTransaction(sendRequest);
        return sendRequest.tx;
    }

    @Override
    public Transaction completeTxWithCustomFee(Transaction transaction,Coin fee) throws InsufficientMoneyException{
        SendRequest sendRequest = SendRequest.forTx(transaction);
        sendRequest.signInputs = true;
        sendRequest.shuffleOutputs = false; // don't shuffle outputs to know the contact
        sendRequest.feePerKb = fee;
        sendRequest.changeAddress = walletManager.newFreshReceiveAddress();
        walletManager.completeSend(sendRequest);
        // if the fee is different to the custom fee and the tx size is lower than 1kb (1000 bytes in pivx core)
        /*if(!sendRequest.tx.getFee().equals(fee) && sendRequest.tx.unsafeBitcoinSerialize().length<1000){
            // re acomodate outputs to include the selected fee
            List<TransactionOutput> oldOutputs = sendRequest.tx.getOutputs();
            sendRequest.tx.clearOutputs();
            for (TransactionOutput oldOutput : oldOutputs) {
                if (oldOutput.getScriptPubKey().getToAddress(getConf().getNetworkParams()).equals(sendRequest.changeAddress)){
                    //nothing
                }else {
                    sendRequest.tx.addOutput(oldOutput);
                }
            }


        }*/

        return sendRequest.tx;
    }

    @Override
    public Coin getUnspentValue(Sha256Hash parentTransactionHash, int index) {
        return walletManager.getUnspentValue(parentTransactionHash,index);
    }

    @Override
    public boolean isAnyPeerConnected() {
        List<Peer> peers = blockchainManager.getConnectedPeers();
        return (blockchainManager != null && peers != null) && !peers.isEmpty();
    }

    @Override
    public long getConnectedPeerHeight() {
        List<Peer> peers = blockchainManager.getConnectedPeers();
        if (blockchainManager!=null &&  peers!=null && !peers.isEmpty()){
            Peer peer = peers.get(0);
            if (peer!=null)
                return peer.getBestHeight();
            else
                return -1;
        }else
            return -1;
    }

    @Override
    public int getProtocolVersion() {
        return blockchainManager.getProtocolVersion();
    }

    @Override
    public void checkMnemonic(List<String> mnemonic) throws MnemonicException {
        walletManager.checkMnemonic(mnemonic);
    }

    @Override
    public WalletConfiguration getConf() {
        return walletConfiguration;
    }


    public List<TransactionWrapper> listTx(Set<Transaction> walletTxes, boolean isPrivate) {
        List<TransactionWrapper> list = new ArrayList<>();
        for (Transaction transaction : walletManager.listTransactions()) {
            boolean isMine = walletManager.isMine(transaction);
            boolean isStaking = false;
            Map<Integer,AddressLabel> outputsLabeled = new HashMap<>();
            Map<Integer,AddressLabel> inputsLabeled = new HashMap<>();
            Address address = null;
            if (isMine){
                try {
                    for (TransactionOutput transactionOutput : transaction.getOutputs()) {
                        Script script = transactionOutput.getScriptPubKey();
                        if (script.isSentToAddress() || script.isPayToScriptHash()) {
                            try {
                                address = script.getToAddress(getConf().getNetworkParams(),true);
                                // if the tx is mine i know that the first output address is the sent and the second one is the change address
                                outputsLabeled.put(transactionOutput.getIndex(), contactsStore.getContact(address.toBase58()));
                            }catch (ScriptException e){
                                logger.warn("unknown tx output, "+script.toString()+", is tx coinbase: "+transaction.isCoinBase());
                                e.printStackTrace();
                            }
                        }else if (script.isSentToRawPubKey()){
                            // is the staking reward
                            address = script.getToAddress(getConf().getNetworkParams(),true);
                            // if the tx is mine i know that the first output address is the sent and the second one is the change address
                            outputsLabeled.put(transactionOutput.getIndex(), contactsStore.getContact(address.toBase58()));
                            isStaking = true;
                        }else {
                            logger.warn("unknown tx output, "+script.toString()+", is tx coinbase: "+transaction.isCoinBase());
                        }
                    }

                    /*for (TransactionInput transactionInput : transaction.getInputs()) {
                        try {
                            address = transactionInput.getScriptSig().getToAddress(getConf().getNetworkParams());
                            // if the tx is mine i know that the first output address is the sent and the second one is the change address
                            inputsLabeled.put((int) transactionInput.getOutpoint().getIndex(), contactsStore.getAddressLabel(address.toBase58()));
                        }catch (ScriptException e){
                            e.printStackTrace();
                        }
                    }*/

                }catch (Exception e){
                    e.printStackTrace();
                    //swallow this for now..
                }
            }else {
                /*for (TransactionOutput transactionOutput : transaction.getOutputs()) {
                    Address addressToCheck = transactionOutput.getScriptPubKey().getToAddress(getConf().getNetworkParams());
                    if(walletManager.isAddressMine(addressToCheck)){
                        address = addressToCheck;
                        break;
                    }
                }*/

                for (TransactionOutput transactionOutput : transaction.getOutputs()) {
                    address = transactionOutput.getScriptPubKey().getToAddress(getConf().getNetworkParams(),true);
                    // if the tx is mine i know that the first output address is the sent and the second one is the change address
                    outputsLabeled.put(transactionOutput.getIndex(),contactsStore.getContact(address.toBase58()));
                }
            }
            TransactionWrapper wrapper;
            if (!isStaking){
                wrapper = new TransactionWrapper(
                        transaction,
                        inputsLabeled,
                        outputsLabeled,
                        isMine ? getValueSentFromMe(transaction,true):walletManager.getValueSentToMe(transaction),
                        isMine ? TransactionWrapper.TransactionUse.SENT_SINGLE: TransactionWrapper.TransactionUse.RECEIVE
                );
            }else {
                wrapper = new TransactionWrapper(
                        transaction,
                        inputsLabeled,
                        outputsLabeled,
                        walletManager.getValueSentToMe(transaction),
                        TransactionWrapper.TransactionUse.STAKE
                );
            }
            list.add(wrapper);
        }
        return list;
    }

    @Override
    public List<TransactionWrapper> listTx(){
        return listTx(walletManager.listTransactions(), false);
    }


    @Override
    public Coin getValueSentFromMe(Transaction transaction, boolean excludeChangeAddress) {
        if (excludeChangeAddress){
            return transaction.getOutput(0).getValue();
        }else
            return walletManager.getValueSentFromMe(transaction);
    }

    @Override
    public void commitTx(Transaction transaction) {
        walletManager.commitTx(transaction);
    }

    @Override
    public List<Peer> listConnectedPeers() {
        return blockchainManager.listConnectedPeers();
    }

    @Override
    public int getChainHeight() {
        return blockchainManager.getChainHeadHeight();
    }


    public BlockchainManager getBlockchainManager() {
        return blockchainManager;
    }


    public void addCoinsReceivedEventListener(WalletCoinsReceivedEventListener coinReceiverListener) {
        walletManager.addCoinsReceivedEventListener(coinReceiverListener);
    }

    public void removeCoinsReceivedEventListener(WalletCoinsReceivedEventListener coinReceiverListener) {
        walletManager.removeCoinsReceivedEventListener(coinReceiverListener);
    }

    public void addOnTransactionConfidenceChange(TransactionConfidenceEventListener transactionConfidenceEventListener) {
        walletManager.addOnTransactionsConfidenceChange(transactionConfidenceEventListener);
    }

    public void removeTransactionsConfidenceChange(TransactionConfidenceEventListener transactionConfidenceEventListener) {
        walletManager.removeTransactionConfidenceChange(transactionConfidenceEventListener);
    }


    public N8VRate getRate(String coin) {
        return rateDb.getRate(coin);
    }

    @Override
    public List<InputWrapper> listUnspentWrappers() {
        List<InputWrapper> inputWrappers = new ArrayList<>();
        for (TransactionOutput transactionOutput : walletManager.listUnspent()) {
            Address address = transactionOutput.getScriptPubKey().getToAddress(getConf().getNetworkParams(),true);
            AddressLabel addressLabel = contactsStore.getContact(address.toBase58());
            inputWrappers.add(
                    new InputWrapper(
                            transactionOutput,
                            addressLabel
                    )

            );
        }
        return inputWrappers;
    }


    @Override
    public Set<InputWrapper> convertFrom(List<TransactionInput> list) throws TxNotFoundException {
        Set<InputWrapper> ret = new HashSet<>();
        for (TransactionInput input : list) {
            TransactionOutput transactionOutput = input.getConnectedOutput();
            if (transactionOutput==null){
                transactionOutput = getUnspent(input.getOutpoint().getHash(), (int) input.getOutpoint().getIndex());
            }
            Address address = transactionOutput.getScriptPubKey().getToAddress(getConf().getNetworkParams(),true);
            AddressLabel addressLabel = contactsStore.getContact(address.toBase58());
            ret.add(
                    new InputWrapper(
                            transactionOutput,
                            addressLabel
                    )
            );
        }
        return ret;
    }

    @Override
    public Transaction getTx(Sha256Hash txId) {
        return walletManager.getTransaction(txId);
    }

    @Override
    public List<String> getMnemonic() {
        return walletManager.getMnemonic();
    }

    @Override
    public String getWatchingPubKey() {
        return walletManager.getExtPubKey();
    }

    @Override
    public DeterministicKey getWatchingKey() {
        return walletManager.getWatchingPubKey();
    }

    @Override
    public DeterministicKey getKeyPairForAddress(Address address) {
        return walletManager.getKeyPairForAddress(address);
    }

    @Override
    public TransactionOutput getUnspent(Sha256Hash parentTxHash, int index) throws TxNotFoundException {
        return walletManager.getUnspent(parentTxHash,index);
    }

    @Override
    public List<TransactionOutput> getRandomUnspentNotInListToFullCoins(List<TransactionInput> inputs, Coin amount) throws InsufficientInputsException {
        return walletManager.getRandomListUnspentNotInListToFullCoins(inputs,amount);
    }

   @Override
   public boolean isSyncWithNode() throws NoPeerConnectedException {
       boolean isSync = false;
       if (isAnyPeerConnected()) {
           long peerHeight = getConnectedPeerHeight();
           if (peerHeight != -1){
               if (getChainHeight()+10 > peerHeight) {
                   isSync = true;
               }
           }
       }else {
          throw new NoPeerConnectedException();
       }
       return isSync;
   }

    @Override
    public void watchOnlyMode(String xpub, DeterministicKeyChain.KeyChainType keyChainType) throws IOException {
        walletManager.watchOnlyMode(xpub,keyChainType);
    }

    @Override
    public boolean isBip32Wallet() {
        return walletManager.isBip32Wallet();
    }

    @Override
    public boolean sweepBalanceToNewSchema() throws InsufficientMoneyException, CantSweepBalanceException {
//        try {
//            logger.info("sweepBalanceToNewSchema");
//
//            // backup the current wallet first
//            File backupFileOld = backupHelper.determineBackupFile("old");
//            backupWallet(backupFileOld,"");

//            // new wallet
//            Wallet newWallet = walletManager.generateRandomWallet();
//            Address sweepAddress = newWallet.freshReceiveAddress();
//            logger.info("sweep address: "+sweepAddress);
//            // sweep old wallet balance
//            Transaction transaction = walletManager.createCleanWalletTx(sweepAddress);
//            logger.info("sweep tx: "+transaction);
//
//            // backup the new wallet
//            File backupFile = backupHelper.determineBackupFile("upgrade");
//            backupWallet(newWallet,backupFile,"");
//
//            // broadcast
//            ListenableFuture<Transaction> future = blockchainManager.broadcastTransaction(transaction);
//            transaction = future.get();
//            logger.info("sweep done: "+future.isDone());
//
//            // wait until the tx is confirmed with 2 blocks
//            ExecutorService executorService = Executors.newSingleThreadExecutor();
//            ListenableFuture<TransactionConfidence> confidenceFuture = transaction.getConfidence().getDepthFuture(2,executorService);
//            TransactionConfidence confidence = confidenceFuture.get();
//
//            if (confidence.getDepthInBlocks()>1){
//                logger.info("Upgrade wallet tx confidence accepted by the network");
//
//            }else {
//                logger.error("ERROR, Upgrade wallet tx confidence not accepted by the network {}",confidence);
//            }
//            // change wallet
//            walletManager.replaceWallet(newWallet);
//            return true;
//        }catch (InterruptedException e) {
//            e.printStackTrace();
//            throw new CantSweepBalanceException(e.getMessage(),e);
//        } catch (ExecutionException e) {
//            e.printStackTrace();
//            throw new CantSweepBalanceException(e.getMessage(),e);
//        } catch (IOException e) {
//            e.printStackTrace();
//            throw new CantSweepBalanceException(e.getMessage(),e);
//        }
        return false;
    }

    @Override
    public boolean upgradeWallet(String upgradeCode) throws UpgradeException {
        try {
            return sweepBalanceToNewSchema();
        } catch (InsufficientMoneyException e) {
            e.printStackTrace();
            throw new UpgradeException(e.getMessage(),e);
        } catch (CantSweepBalanceException e) {
            throw new UpgradeException(e.getMessage(),e);
        }
    }

    @Override
    public List<N8VRate> listRates() {
        return rateDb.list();
    }

    @Override
    public List<String> getAvailableMnemonicWordsList() {
        return walletManager.getAvailableMnemonicWordsList();
    }

    @Override
    public boolean encrypt(String password) throws UnsupportedEncodingException {
        password = new String(Base64.encode(password.getBytes("UTF-8")), StandardCharsets.UTF_8);
        walletManager.getWallet().encrypt(password);
        logger.info("Wallet encrypted");
        return true;
    }

    public boolean encrypt(KeyCrypter keyCrypter,KeyParameter keyParameter) {
        walletManager.getWallet().encrypt(keyCrypter, keyParameter);
        logger.info("Wallet encrypted");
        return true;
    }

    @Override
    public boolean decrypt(String password) throws UnsupportedEncodingException {
        password = new String(Base64.encode(password.getBytes("UTF-8")), StandardCharsets.UTF_8);
        walletManager.getWallet().decrypt(password);
        logger.info("Wallet decrypted");
        return true;
    }


    public boolean decrypt(KeyParameter keyParameter) {
        walletManager.getWallet().decrypt(keyParameter);
        logger.info("Wallet decrypted");
        return true;
    }

    @Override
    public boolean isWalletLocked() {
        return walletManager.getWallet().isEncrypted();
    }

    @Override
    public boolean isStarted() {
        return walletManager.isStarted();
    }

    @Override
    public boolean isStarting() {
        return walletManager.isStarting();
    }


    public void saveRate(N8VRate n8VRate){
        rateDb.insertOrUpdateIfExist(n8VRate);
    }


    public Wallet getWallet(){
        return walletManager.getWallet();
    }
}
