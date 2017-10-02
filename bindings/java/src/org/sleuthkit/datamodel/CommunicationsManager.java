/*
 * Sleuth Kit Data Model
 *
 * Copyright 2011-2017 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.datamodel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.sleuthkit.datamodel.SleuthkitCase.CaseDbConnection;
import static org.sleuthkit.datamodel.SleuthkitCase.closeResultSet;
import static org.sleuthkit.datamodel.SleuthkitCase.closeStatement;

/**
 * Provides an API to create Accounts and communications/relationships between
 * accounts
 */
public class CommunicationsManager {

	private static final Logger LOGGER = Logger.getLogger(CommunicationsManager.class.getName());

	private final SleuthkitCase db;

	private Map<Account.Type, Integer> accountTypeToTypeIdMap;
	private Map<String, Account.Type> typeNameToAccountTypeMap;

	CommunicationsManager(SleuthkitCase db) throws TskCoreException {
		this.db = db;

		init();
	}

	private void init() throws TskCoreException {
		accountTypeToTypeIdMap = new ConcurrentHashMap<Account.Type, Integer>();
		typeNameToAccountTypeMap = new ConcurrentHashMap<String, Account.Type>();

		initAccountTypes();
	}

	/**
	 * Make sure the predefined account types are in the account types table.
	 *
	 * @throws SQLException
	 * @throws TskCoreException
	 */
	private void initAccountTypes() throws TskCoreException {
		CaseDbConnection connection = db.getConnection();
		Statement statement = null;
		ResultSet resultSet = null;

		try {
			statement = connection.createStatement();
			// Read the table
			int count = readAccountTypes();
			if (0 == count) {
				// Table is empty, populate it with predefined types
				for (Account.Type type : Account.Type.PREDEFINED_ACCOUNT_TYPES) {
					try {
						statement.execute("INSERT INTO account_types (type_name, display_name) VALUES ( '" + type.getTypeName() + "', '" + type.getDisplayName() + "')"); //NON-NLS
					} catch (SQLException ex) {
						resultSet = connection.executeQuery(statement, "SELECT COUNT(*) AS count FROM account_types WHERE type_name = '" + type.getTypeName() + "'"); //NON-NLS
						resultSet.next();
						if (resultSet.getLong("count") == 0) {
							throw ex;
						}
						resultSet.close();
						resultSet = null;
					}

					ResultSet rs2 = null;
					rs2 = connection.executeQuery(statement, "SELECT account_type_id FROM account_types WHERE type_name = '" + type.getTypeName() + "'"); //NON-NLS
					rs2.next();
					int typeID = rs2.getInt("account_type_id");
					rs2.close();
					rs2 = null;

					Account.Type accountType = new Account.Type(type.getTypeName(), type.getDisplayName());
					this.accountTypeToTypeIdMap.put(accountType, typeID);
					this.typeNameToAccountTypeMap.put(type.getTypeName(), accountType);
				}
			}
		} catch (SQLException ex) {
			LOGGER.log(Level.SEVERE, "Failed to add row to account_types", ex);
		} finally {
			closeResultSet(resultSet);
			closeStatement(statement);
			connection.close();
		}
	}

	/**
	 * Reads in in the account types table.
	 *
	 * Returns the number of account types read in
	 *
	 * @throws SQLException
	 * @throws TskCoreException
	 */
	private int readAccountTypes() throws SQLException, TskCoreException {
		CaseDbConnection connection = db.getConnection();
		Statement statement = null;
		ResultSet resultSet = null;

		int count = 0;
		try {
			statement = connection.createStatement();

			// If the account_types table is already populated, say when opening a case,  then load it
			resultSet = connection.executeQuery(statement, "SELECT COUNT(*) AS count FROM account_types"); //NON-NLS
			resultSet.next();
			if (resultSet.getLong("count") > 0) {

				resultSet.close();

				resultSet = connection.executeQuery(statement, "SELECT * FROM account_types");
				while (resultSet.next()) {
					Account.Type accountType = new Account.Type(resultSet.getString("type_name"), resultSet.getString("display_name"));
					this.accountTypeToTypeIdMap.put(accountType, resultSet.getInt("account_type_id"));
					this.typeNameToAccountTypeMap.put(accountType.getTypeName(), accountType);
				}
				count = this.typeNameToAccountTypeMap.size();
			}

		} catch (SQLException ex) {
			LOGGER.log(Level.SEVERE, "Failed to read account_types", ex);
		} finally {
			closeResultSet(resultSet);
			closeStatement(statement);
			connection.close();
		}

		return count;
	}

	/**
	 * Add an account type
	 *
	 * @param accountTypeName account type name
	 * @param displayName     account type display name
	 *
	 * @return Account.Type
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public Account.Type addAccountType(String accountTypeName, String displayName) throws TskCoreException {

		Account.Type accountType = new Account.Type(accountTypeName, displayName);

		// check if already in map
		if (this.accountTypeToTypeIdMap.containsKey(accountType)) {
			return accountType;
		}

		CaseDbConnection connection = db.getConnection();
		db.acquireExclusiveLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			connection.beginTransaction();
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT * FROM account_types WHERE type_name = '" + accountTypeName + "'"); //NON-NLS
			if (!rs.next()) {
				rs.close();

				s.execute("INSERT INTO account_types (type_name, display_name) VALUES ( '" + accountTypeName + "', '" + displayName + "')"); //NON-NLS

				// Read back the typeID
				rs = connection.executeQuery(s, "SELECT * FROM account_types WHERE type_name = '" + accountTypeName + "'"); //NON-NLS
				rs.next();

				int typeID = rs.getInt("account_type_id");
				accountType = new Account.Type(rs.getString("type_name"), rs.getString("display_name"));
				
				this.accountTypeToTypeIdMap.put(accountType, typeID);
				this.typeNameToAccountTypeMap.put(accountTypeName, accountType);

				connection.commitTransaction();

				return accountType;
			} else {
				int typeID = rs.getInt("account_type_id");

				accountType = new Account.Type(rs.getString("type_name"), rs.getString("display_name"));
				this.accountTypeToTypeIdMap.put(accountType, typeID);

				return accountType;
			}

		} catch (SQLException ex) {
			connection.rollbackTransaction();
			throw new TskCoreException("Error adding account type", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseExclusiveLock();
		}
	}

	/**
	 * Create an AccountInstance with the given account type and account ID, and
	 * sourceObj. if it doesn't exist already
	 *
	 *
	 * @param accountType     account type
	 * @param accountUniqueID unique account identifier
	 * @param moduleName      module creating the account
	 * @param sourceObj       source content
	 *
	 * @return AccountInstance
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public AccountInstance createAccountInstance(Account.Type accountType, String accountUniqueID, String moduleName, Content sourceObj) throws TskCoreException {
		AccountInstance accountInstance = null;
		long accountId = getOrCreateAccount(accountType, normalizeAccountID(accountType, accountUniqueID)).getAccountId();

		BlackboardArtifact accountArtifact = getOrCreateAccountInstanceArtifact(BlackboardArtifact.ARTIFACT_TYPE.TSK_ACCOUNT, accountType, normalizeAccountID(accountType, accountUniqueID), moduleName, sourceObj);
		accountInstance = new AccountInstance(this.db, accountArtifact.getArtifactID(), accountId);

		// add a row to Accounts to Instances mapping table
		addAccountInstanceMapping(accountId, accountArtifact.getArtifactID());

		return accountInstance;
	}

	/**
	 * Get the Account with the given account type and account ID.
	 *
	 * @param accountType     account type
	 * @param accountUniqueID unique account identifier
	 *
	 * @return Account, returns NULL is no matching account found
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public Account getAccount(Account.Type accountType, String accountUniqueID) throws TskCoreException {

		Account account = null;

		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT * FROM accounts WHERE account_type_id = " + getAccountTypeId(accountType)
					+ " AND account_unique_identifier = '" + accountUniqueID + "'"); //NON-NLS

			if (rs.next()) {
				account = new Account(rs.getInt("account_id"), accountType,
						rs.getString("account_unique_identifier"));
			}

		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account type id", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}

		return account;
	}

	/**
	 * Returns an account instance for the given account instance artifact
	 *
	 * @param artifact
	 *
	 * @return Account, returns NULL is no matching account found
	 *
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 *
	 */
	public AccountInstance getAccountInstance(BlackboardArtifact artifact) throws TskCoreException {
		AccountInstance accountInstance = null;
		if (artifact.getArtifactTypeID() == BlackboardArtifact.ARTIFACT_TYPE.TSK_ACCOUNT.getTypeID()) {
			String accountTypeStr = artifact.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ACCOUNT_TYPE)).getValueString();
			String accountID = artifact.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ID)).getValueString();
			Account.Type accountType = getAccountType(accountTypeStr);

			Account account = getAccount(accountType, accountID);
			accountInstance = new AccountInstance(this.db, artifact, account);
		}

		return accountInstance;
	}

	/**
	 * Get all account types in use
	 *
	 * @return List <Account.Type>, list of account types in use
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public List<Account.Type> getAccountTypesInUse() throws TskCoreException {
		String query = "SELECT DISTINCT value_text FROM blackboard_attributes "
				+ "WHERE atrribute_type_id = " + BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ACCOUNT_TYPE.getTypeID();
		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, query);
			ArrayList<Account.Type> usedAccountTypes = new ArrayList<Account.Type>();
			while (rs.next()) {
				String accountTypeString = rs.getString("value_text");
				usedAccountTypes.add(getAccountType(accountTypeString));
			}
			return usedAccountTypes;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account types in use", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}
	}

	/**
	 * Get all accounts of given type
	 *
	 * @param accountType account type
	 *
	 * @return List <Account.Type>, list of accounts
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public List<Account> getAccounts(Account.Type accountType) throws TskCoreException {
		ArrayList<Account> accounts = new ArrayList<Account>();

		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();

			rs = connection.executeQuery(s, "SELECT * FROM accounts WHERE account_type_id = " + getAccountTypeId(accountType)); //NON-NLS
			while (rs.next()) {
				accounts.add(new Account(rs.getInt("account_id"), accountType,
						rs.getString("account_unique_identifier")));

			}
			return accounts;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting accounts by type. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}
	}

	/**
	 * Get all account instances of a given type
	 *
	 * @param accountType account type
	 *
	 * @return List <Account.Type>, list of accounts
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public List<AccountInstance> getAccountInstances(Account.Type accountType) throws TskCoreException {

		List<AccountInstance> accountInstances = new ArrayList<AccountInstance>();

		// First get all account of the type
		List<Account> accounts = getAccounts(accountType);

		// get all instances for each account
		for (Account account : accounts) {
			List<Long> accountInstanceIds = getAccountInstanceIds(account.getAccountId());

			for (long artifact_id : accountInstanceIds) {
				accountInstances.add(new AccountInstance(db, artifact_id, account.getAccountId()));
			}
		}
		return accountInstances;
	}

	/**
	 * Reject the given account instance
	 *
	 * @param accountInstance account instance to reject
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	/**
	 * Get all accounts that have a relationship with the given account
	 *
	 * @param account account for which to search relationships
	 *
	 * @return list of accounts with relationships to the given account
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public List<Account> getAccountsWithRelationship(Account account) throws TskCoreException {
		return getAccountsWithRelationship(account.getAccountId());
	}

	/**
	 * Add a relationship between the given sender and recipient account
	 * instances.
	 *
	 * @param sender                sender account
	 * @param recipients            list of recipients
	 * @param communicationArtifact communication item
	 */
	public void addRelationships(AccountInstance sender, List<AccountInstance> recipients, BlackboardArtifact communicationArtifact) {

		// Currently we do not save the direction of communication
		List<Long> accountIDs = new ArrayList<Long>();
		if (null != sender) {
			accountIDs.add(sender.getAccountId());
		}

		for (AccountInstance recipient : recipients) {
			accountIDs.add(recipient.getAccountId());
		}

		Set<UnorderedAccountPair> relationships = listToUnorderedPairs(accountIDs);
		Iterator<UnorderedAccountPair> iter = relationships.iterator();

		while (iter.hasNext()) {
			try {
				UnorderedAccountPair accountPair = iter.next();
				addAccountsRelationship(accountPair.getFirst(), accountPair.getSecond(), communicationArtifact.getArtifactID());
			} catch (TskCoreException ex) {
				LOGGER.log(Level.WARNING, "Could not get timezone for image", ex); //NON-NLS
			}
		}

	}

	/**
	 * Returns unique relation types between two accounts
	 *
	 * @param account1 account
	 * @param account2 account
	 *
	 * @return list of unique relationship types between two accounts
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public List<BlackboardArtifact.Type> getRelationshipTypes(Account account1, Account account2) throws TskCoreException {
		return getRelationshipTypes(account1.getAccountId(), account2.getAccountId());
	}

	/**
	 * Returns relationships between two accounts
	 *
	 * @param account1 account
	 * @param account2 account
	 *
	 * @return relationships between two accounts
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public List<BlackboardArtifact> getRelationships(Account account1, Account account2) throws TskCoreException {

		return getRelationships(account1.getAccountId(), account2.getAccountId());
	}

	/**
	 * Returns relationships of specified type between two accounts
	 *
	 * @param account1     one account in relationship
	 * @param account2     other account in relationship
	 * @param artifactType relationship type
	 *
	 * @return list of relationships
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public List<BlackboardArtifact> getRelationshipsOfType(Account account1, Account account2, BlackboardArtifact.Type artifactType) throws TskCoreException {

		return getRelationshipsOfType(account1.getAccountId(), account2.getAccountId(), artifactType);
	}

	/**
	 * Return folders found in the email source file
	 *
	 * @param srcObjID pbjectID of the email PST/Mbox source file
	 *
	 * @return list of message folders
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public List<MessageFolder> getMessageFolders(long srcObjID) throws TskCoreException {
		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT DISTINCT attributes.value_text AS folder_path"
					+ " FROM blackboard_artifacts AS artifacts"
					+ "	JOIN blackboard_attributes AS attributes"
					+ "		ON artifacts.artifact_id = attributes.artifact_id"
					+ "		AND artifacts.obj_id = " + srcObjID
					+ " WHERE artifacts.artifact_type_id = " + BlackboardArtifact.ARTIFACT_TYPE.TSK_EMAIL_MSG.getTypeID()
					+ "     AND attributes.attribute_type_id =  " + BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PATH.getTypeID()
			); //NON-NLS

			ArrayList<MessageFolder> messageFolders = new ArrayList<MessageFolder>();
			while (rs.next()) {

				String folder = rs.getString("folder_path");

				// TBD: check if this folder has subfolders and set hasSubFolders accordingly.
				messageFolders.add(new MessageFolder(folder, srcObjID));
			}
			return messageFolders;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting message folders. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}

	}

	/**
	 * Return subfolders found in the email source file under the specified
	 * folder
	 *
	 * @param srcObjID     objectID of the email PST/Mbox source file
	 * @param parentfolder parent folder of messages to return
	 *
	 * @return list of message sub-folders
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public List<MessageFolder> getMessageFolders(long srcObjID, MessageFolder parentfolder) throws TskCoreException {
		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT DISTINCT attributes.value_text AS folder_path"
					+ " FROM blackboard_artifacts AS artifacts"
					+ "	JOIN blackboard_attributes AS attributes"
					+ "		ON artifacts.artifact_id = attributes.artifact_id"
					+ "		AND artifacts.obj_id = " + srcObjID
					+ "     WHERE artifacts.artifact_type_id = " + BlackboardArtifact.ARTIFACT_TYPE.TSK_EMAIL_MSG.getTypeID()
					+ " AND attributes.attribute_type_id =  " + BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PATH.getTypeID()
					+ " AND attributes.value_text LIKE '" + parentfolder.getName() + "%'"
			); //NON-NLS

			ArrayList<MessageFolder> messageFolders = new ArrayList<MessageFolder>();
			while (rs.next()) {

				String folder = rs.getString("folder_path");
				messageFolders.add(new MessageFolder(folder, srcObjID));
			}
			return messageFolders;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting message folders. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}

	}

	/**
	 * Return email messages under given folder
	 *
	 * @param parentfolder parent folder of messages to return
	 *
	 * @return list of messages
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public List<BlackboardArtifact> getMessages(MessageFolder parentfolder) throws TskCoreException {
		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT artifacts.artifact_id AS artifact_id,"
					+ " artifacts.obj_id AS obj_id,"
					+ " artifacts.artifact_obj_id AS artifact_obj_id,"
					+ " artifacts.artifact_type_id AS artifact_type_id,"
					+ " artifacts.review_status_id AS review_status_id,  "
					+ " FROM blackboard_artifacts AS artifacts"
					+ "	JOIN blackboard_attributes AS attributes"
					+ "		ON artifacts.artifact_id = attributes.artifact_id"
					+ "		AND artifacts.artifact_type_id = " + BlackboardArtifact.ARTIFACT_TYPE.TSK_EMAIL_MSG.getTypeID()
					+ " WHERE attributes.attribute_type_id =  " + BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PATH.getTypeID()
					+ " AND attributes.value_text  =  '" + parentfolder.getName() + "' "
			); //NON-NLS

			ArrayList<BlackboardArtifact> artifacts = new ArrayList<BlackboardArtifact>();
			while (rs.next()) {

				BlackboardArtifact.Type bbartType = db.getArtifactType(rs.getInt("artifact_type_id"));
				artifacts.add(new BlackboardArtifact(db, rs.getLong("artifact_id"), rs.getLong("obj_id"), rs.getLong("artifact_obj_id"),
						bbartType.getTypeID(), bbartType.getTypeName(), bbartType.getDisplayName(),
						BlackboardArtifact.ReviewStatus.withID(rs.getInt("review_status_id"))));
			}
			return artifacts;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting messages. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}

	}

	/**
	 * Get the Account for the given account type and account ID. Create an a
	 * new account if one doesn't exist
	 *
	 * @param artifactType    artifact type - will be TSK_ACCOUNT
	 * @param accountType     account type
	 * @param accountUniqueID unique account identifier
	 *
	 * @return blackboard artifact, returns NULL is no matching account found
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	Account getOrCreateAccount(Account.Type accountType, String accountUniqueID) throws TskCoreException {
		Account account = null;

		account = getAccount(accountType, accountUniqueID);
		if (null == account) {

			CaseDbConnection connection = db.getConnection();
			db.acquireExclusiveLock();
			Statement s = null;
			ResultSet rs = null;
			try {
				connection.beginTransaction();
				s = connection.createStatement();

				s.execute("INSERT INTO accounts (account_type_id, account_unique_identifier) VALUES ( " + getAccountTypeId(accountType) + ", '" + accountUniqueID + "'" + ")"); //NON-NLS

				connection.commitTransaction();

				account = getAccount(accountType, accountUniqueID);

			} catch (SQLException ex) {
				connection.rollbackTransaction();
				throw new TskCoreException("Error adding an account", ex);
			} finally {
				closeResultSet(rs);
				closeStatement(s);
				connection.close();
				db.releaseExclusiveLock();
			}
		}

		return account;
	}

	/**
	 * Get the blackboard artifact for the given account type and account ID.
	 * Create an artifact and return that, of a matching doesn't exists
	 *
	 * @param artifactType    artifact type - will be TSK_ACCOUNT
	 * @param accountType     account type
	 * @param accountUniqueID accountID
	 *
	 * @return blackboard artifact, returns NULL is no matching account found
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	BlackboardArtifact getOrCreateAccountInstanceArtifact(BlackboardArtifact.ARTIFACT_TYPE artifactType, Account.Type accountType, String accountUniqueID, String moduleName, Content sourceObj) throws TskCoreException {
		BlackboardArtifact accountArtifact = getAccountInstanceArtifact(artifactType, accountType, accountUniqueID, sourceObj);

		if (null != accountArtifact) {
			return accountArtifact;
		}

		// Create a new artifact.
		accountArtifact = db.newBlackboardArtifact(artifactType.getTypeID(), sourceObj.getId());

		Collection<BlackboardAttribute> attributes = new ArrayList<BlackboardAttribute>();
		attributes.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ACCOUNT_TYPE, moduleName, accountType.getTypeName()));
		attributes.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ID, moduleName, accountUniqueID));
		accountArtifact.addAttributes(attributes);

		return accountArtifact;
	}

	/**
	 * Get the blackboard artifact for the given account type and account ID
	 *
	 * @param artifactType    artifact type - will be TSK_ACCOUNT
	 * @param accountType     account type
	 * @param accountUniqueID accountID
	 *
	 * @return blackboard artifact, returns NULL is no matching account found
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	BlackboardArtifact getAccountInstanceArtifact(BlackboardArtifact.ARTIFACT_TYPE artifactType, Account.Type accountType, String accountUniqueID, Content sourceObj) throws TskCoreException {
		BlackboardArtifact accountArtifact = null;

		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			String queryStr = "SELECT artifacts.artifact_id AS artifact_id,"
					+ " artifacts.obj_id AS obj_id,"
					+ " artifacts.artifact_obj_id AS artifact_obj_id,"
					+ " artifacts.artifact_type_id AS artifact_type_id,"
					+ " artifacts.review_status_id AS review_status_id"
					+ " FROM blackboard_artifacts AS artifacts"
					+ "	JOIN blackboard_attributes AS attr_account_type"
					+ "		ON artifacts.artifact_id = attr_account_type.artifact_id"
					+ " JOIN blackboard_attributes AS attr_account_id"
					+ "		ON artifacts.artifact_id = attr_account_id.artifact_id"
					+ "		AND attr_account_id.attribute_type_id = " + BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ID.getTypeID()
					+ "	    AND attr_account_id.value_text = '" + accountUniqueID + "'"
					+ " WHERE artifacts.artifact_type_id = " + artifactType.getTypeID()
					+ " AND attr_account_type.attribute_type_id = " + BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ACCOUNT_TYPE.getTypeID()
					+ " AND attr_account_type.value_text = '" + accountType.getTypeName() + "'"
					+ " AND artifacts.obj_id = " + sourceObj.getId(); //NON-NLS

			rs = connection.executeQuery(s, queryStr); //NON-NLS
			if (rs.next()) {
				BlackboardArtifact.Type bbartType = db.getArtifactType(rs.getInt("artifact_type_id"));

				accountArtifact = new BlackboardArtifact(db, rs.getLong("artifact_id"), rs.getLong("obj_id"), rs.getLong("artifact_obj_id"),
						bbartType.getTypeID(), bbartType.getTypeName(), bbartType.getDisplayName(),
						BlackboardArtifact.ReviewStatus.withID(rs.getInt("review_status_id")));

			}

		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}

		return accountArtifact;
	}

	void addAccountInstanceMapping(long accountId, long accountInstanceId) throws TskCoreException {

		CaseDbConnection connection = db.getConnection();
		db.acquireExclusiveLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			connection.beginTransaction();
			s = connection.createStatement();

			s.execute("INSERT INTO account_to_instances_map (account_id, account_instance_id) VALUES ( " + accountId + ", " + accountInstanceId + " )"); //NON-NLS
			connection.commitTransaction();
		} catch (SQLException ex) {
			connection.rollbackTransaction();
			throw new TskCoreException("Error adding an account to instance mapping", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseExclusiveLock();
		}
	}

	/**
	 * Get the Account.Type for the give type name.
	 *
	 * @param accountTypeName An attribute type name.
	 *
	 * @return An attribute type or null if the attribute type does not exist.
	 *
	 * @throws TskCoreException If an error occurs accessing the case database.
	 *
	 */
	Account.Type getAccountType(String accountTypeName) throws TskCoreException {
		if (this.typeNameToAccountTypeMap.containsKey(accountTypeName)) {
			return this.typeNameToAccountTypeMap.get(accountTypeName);
		}
		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT account_type_id, type_name, display_name, value_type FROM account_types WHERE type_name = '" + accountTypeName + "'"); //NON-NLS
			Account.Type accountType = null;
			if (rs.next()) {
				accountType = new Account.Type(accountTypeName, rs.getString("display_name"));
				this.accountTypeToTypeIdMap.put(accountType, rs.getInt("account_type_id"));
				this.typeNameToAccountTypeMap.put(accountTypeName, accountType);
			}
			return accountType;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account type id", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}
	}

	/**
	 * Get the Account object for the given account_id returns null, if does not
	 * exist
	 *
	 * @param account_id account_id
	 *
	 * @return Account, returns NULL is no matching account found
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	Account getAccount(long account_id) throws TskCoreException {
		Account account = null;

		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT account_types.type_name as type_name"
					+ " account_types.display_name as display_name"
					+ " accounts.account_id as account_id"
					+ " accounts.account_unique_identifier as account_unique_identifier"
					+ " FROM accounts"
					+ " JOIN account_types"
					+ " ON accounts.account_type_id = account_types.account_type_id"
					+ " WHERE accounts.account_id = " + account_id); //NON-NLS

			if (rs.next()) {
				Account.Type accountType = new Account.Type(rs.getString("type_name"), rs.getString("display_name"));
				account = new Account(rs.getInt("account_id"), accountType, rs.getString("account_unique_identifier"));
			}

		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account from account_id", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}

		return account;
	}

	/**
	 * Given an account ID, returns the ids of all instances of the account
	 *
	 * @param account_id account id
	 *
	 * @return List <Long>, list of account instance IDs
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	List<Long> getAccountInstanceIds(long account_id) throws TskCoreException {

		ArrayList<Long> accountInstanceIDs = new ArrayList<Long>();

		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();

			rs = connection.executeQuery(s, "SELECT * FROM account_to_instances_map WHERE account_id = " + account_id); //NON-NLS
			while (rs.next()) {
				accountInstanceIDs.add(rs.getLong("account_instance_id"));
			}
			return accountInstanceIDs;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account_instance_id from by account_id. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}
	}

	/**
	 * Get all account that have a relationship with a given account
	 *
	 * @param account_id account id
	 *
	 * @return List<Account>, list of accounts
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	List<Account> getAccountsWithRelationship(long accountID) throws TskCoreException {

		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT account1_id, account2_id "
					+ " FROM relationships"
					+ " WHERE  account1_id = " + accountID
					+ "        OR  account2_id = " + accountID); //NON-NLS

			ArrayList<Account> accounts = new ArrayList<Account>();
			while (rs.next()) {

				long otherAccountID = (accountID == rs.getLong("account1_id")) ? rs.getLong("account2_id") : rs.getLong("account1_id");
				Account account = getAccount(otherAccountID);
				if (null != account) {
					accounts.add(account);
				}

			}
			return accounts;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting relationships by account by ID. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}
	}

	/**
	 * Adds a row in relationships table
	 *
	 * @param account1_id account_id for account1
	 * @param account2_id account_id for account2
	 * @param artifactID  artifact id for communication item
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	void addAccountsRelationship(long account1_id, long account2_id, long artifactID) throws TskCoreException {

		CaseDbConnection connection = db.getConnection();
		db.acquireExclusiveLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			connection.beginTransaction();
			s = connection.createStatement();

			s.execute("INSERT INTO relationships (account1_id, account2_id, communication_artifact_id) VALUES ( " + account1_id + ", " + account2_id + ", " + artifactID + ")"); //NON-NLS

			connection.commitTransaction();

		} catch (SQLException ex) {
			connection.rollbackTransaction();
			throw new TskCoreException("Error adding accounts relationship", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseExclusiveLock();
		}

	}

	/**
	 * Returns unique relation types between two accounts
	 *
	 * @param account1_id account1 artifact ID
	 * @param account2_id account2 artifact ID
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	List<BlackboardArtifact.Type> getRelationshipTypes(long account1_id, long account2_id) throws TskCoreException {

		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT DISTINCT artifacts.artifact_id AS artifact_id,"
					+ " artifacts.obj_id AS obj_id,"
					+ " artifacts.artifact_obj_id AS artifact_obj_id,"
					+ " artifacts.artifact_type_id AS artifact_type_id,"
					+ " artifacts.review_status_id AS review_status_id"
					+ " FROM blackboard_artifacts AS artifacts"
					+ "	JOIN relationships AS relationships"
					+ "		ON artifacts.artifact_id = relationships.communication_artifact_id"
					+ " WHERE relationships.account1_id IN ( " + account1_id + ", " + account2_id + " )"
					+ " AND relationships.account2_id IN ( " + account1_id + ", " + account2_id + " )"
			); //NON-NLS

			ArrayList<BlackboardArtifact.Type> artifactTypes = new ArrayList<BlackboardArtifact.Type>();
			while (rs.next()) {

				BlackboardArtifact.Type bbartType = db.getArtifactType(rs.getInt("artifact_type_id"));
				artifactTypes.add(bbartType);
			}
			return artifactTypes;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting relationship types." + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}
	}

	/**
	 * Returns relationships between two accounts
	 *
	 * @param account1_id account_id for account1
	 * @param account2_id account_id for account2
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	List<BlackboardArtifact> getRelationships(long account1_id, long account2_id) throws TskCoreException {

		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT artifacts.artifact_id AS artifact_id,"
					+ " artifacts.obj_id AS obj_id,"
					+ " artifacts.artifact_obj_id AS artifact_obj_id,"
					+ " artifacts.artifact_type_id AS artifact_type_id,"
					+ " artifacts.review_status_id AS review_status_id"
					+ " FROM blackboard_artifacts AS artifacts"
					+ "	JOIN relationships AS relationships"
					+ "		ON artifacts.artifact_id = relationships.communication_artifact_id"
					+ " WHERE relationships.account1_id IN ( " + account1_id + ", " + account2_id + " )"
					+ " AND relationships.account2_id IN ( " + account1_id + ", " + account2_id + " )"
			); //NON-NLS

			ArrayList<BlackboardArtifact> artifacts = new ArrayList<BlackboardArtifact>();
			while (rs.next()) {

				BlackboardArtifact.Type bbartType = db.getArtifactType(rs.getInt("artifact_type_id"));
				artifacts.add(new BlackboardArtifact(db, rs.getLong("artifact_id"), rs.getLong("obj_id"), rs.getLong("artifact_obj_id"),
						bbartType.getTypeID(), bbartType.getTypeName(), bbartType.getDisplayName(),
						BlackboardArtifact.ReviewStatus.withID(rs.getInt("review_status_id"))));
			}
			return artifacts;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting relationships bteween accounts. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}
	}

	/**
	 * Returns relationships, of given type, between two accounts
	 *
	 * @param account1_id  account1 artifact ID
	 * @param account2_id  account2 artifact ID
	 * @param artifactType artifact type
	 *
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	List<BlackboardArtifact> getRelationshipsOfType(long account1_id, long account2_id, BlackboardArtifact.Type artifactType) throws TskCoreException {

		CaseDbConnection connection = db.getConnection();
		db.acquireSharedLock();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = connection.createStatement();
			rs = connection.executeQuery(s, "SELECT artifacts.artifact_id AS artifact_id,"
					+ " artifacts.obj_id AS obj_id,"
					+ " artifacts.artifact_obj_id AS artifact_obj_id,"
					+ " artifacts.artifact_type_id AS artifact_type_id,"
					+ " artifacts.review_status_id AS review_status_id"
					+ " FROM blackboard_artifacts AS artifacts"
					+ "	JOIN relationships AS relationships"
					+ "		ON artifacts.artifact_id = relationships.communication_artifact_id"
					+ "     WHERE artifacts.artifact_type_id = " + artifactType.getTypeID()
					+ " WHERE relationships.account1_id IN ( " + account1_id + ", " + account2_id + " )"
					+ " AND relationships.account2_id IN ( " + account1_id + ", " + account2_id + " )"
			); //NON-NLS

			ArrayList<BlackboardArtifact> artifacts = new ArrayList<BlackboardArtifact>();
			while (rs.next()) {

				BlackboardArtifact.Type bbartType = db.getArtifactType(rs.getInt("artifact_type_id"));
				artifacts.add(new BlackboardArtifact(db, rs.getLong("artifact_id"), rs.getLong("obj_id"), rs.getLong("artifact_obj_id"),
						bbartType.getTypeID(), bbartType.getTypeName(), bbartType.getDisplayName(),
						BlackboardArtifact.ReviewStatus.withID(rs.getInt("review_status_id"))));
			}
			return artifacts;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting relationships bteween accounts. " + ex.getMessage(), ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
			connection.close();
			db.releaseSharedLock();
		}
	}

	/**
	 * Get account_type_if for the given account type
	 *
	 * @param accountType account type to lookup
	 *
	 * @return account_type_id for the given account type. 0 if not known.
	 */
	private int getAccountTypeId(Account.Type accountType) {

		if (accountTypeToTypeIdMap.containsKey(accountType)) {
			return accountTypeToTypeIdMap.get(accountType);
		}

		return -1;
	}

	/**
	 * Converts a list of accountIDs into a set of possible unordered pairs
	 *
	 * @param accountIDs - list of accountID
	 *
	 * @return Set<UnorderedPair<Long>>
	 */
	private Set<UnorderedAccountPair> listToUnorderedPairs(List<Long> account_ids) {
		Set<UnorderedAccountPair> relationships = new HashSet<UnorderedAccountPair>();

		for (int i = 0; i < account_ids.size(); i++) {
			for (int j = i + 1; j < account_ids.size(); j++) {
				relationships.add(new UnorderedAccountPair(account_ids.get(i), account_ids.get(j)));
			}
		}

		return relationships;
	}

	private String normalizeAccountID(Account.Type accountType, String accountUniqueID) {
		String normailzeAccountID = accountUniqueID;

		if (accountType == Account.Type.PHONE) {
			normailzeAccountID = normalizePhoneNum(accountUniqueID);
		}

		return normailzeAccountID;
	}

	private String normalizePhoneNum(String phoneNum) {

		String normailzedPhoneNum = phoneNum.replaceAll("\\D", "");
		if (phoneNum.startsWith("+")) {
			normailzedPhoneNum = "+" + normailzedPhoneNum;
		}

		return normailzedPhoneNum;
	}

	/*
	 * Class representing an unordered pair of account ids. <a,b> is same as
	 * <b,a>
	 */
	public final class UnorderedAccountPair {

		private final long account1_id;
		private final long account2_id;

		public UnorderedAccountPair(long account1_id, long account2_id) {
			this.account1_id = account1_id;
			this.account2_id = account2_id;
		}

		@Override
		public int hashCode() {
			return new Long(account1_id).hashCode() + new Long(account2_id).hashCode();
		}

		@Override
		public boolean equals(Object other) {
			if (other == this) {
				return true;
			}
			if (!(other instanceof UnorderedAccountPair)) {
				return false;
			}

			UnorderedAccountPair otherPair = (UnorderedAccountPair) other;
			return ((account1_id == otherPair.account1_id && account2_id == otherPair.account2_id)
					|| (account1_id == otherPair.account2_id && account2_id == otherPair.account1_id));
		}

		public long getFirst() {
			return account1_id;
		}

		public long getSecond() {
			return account2_id;
		}
	}

}
