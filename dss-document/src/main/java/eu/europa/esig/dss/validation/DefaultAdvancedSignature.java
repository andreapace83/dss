/**
 * DSS - Digital Signature Services
 * Copyright (C) 2015 European Commission, provided under the CEF programme
 * 
 * This file is part of the "DSS - Digital Signature Services" project.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package eu.europa.esig.dss.validation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.europa.esig.dss.CertificateReorderer;
import eu.europa.esig.dss.DSSASN1Utils;
import eu.europa.esig.dss.DSSDocument;
import eu.europa.esig.dss.DSSException;
import eu.europa.esig.dss.DSSRevocationUtils;
import eu.europa.esig.dss.DSSUtils;
import eu.europa.esig.dss.DigestAlgorithm;
import eu.europa.esig.dss.SignatureLevel;
import eu.europa.esig.dss.TokenIdentifier;
import eu.europa.esig.dss.utils.Utils;
import eu.europa.esig.dss.x509.CertificatePool;
import eu.europa.esig.dss.x509.CertificateSourceType;
import eu.europa.esig.dss.x509.CertificateToken;
import eu.europa.esig.dss.x509.RevocationToken;
import eu.europa.esig.dss.x509.SignaturePolicy;
import eu.europa.esig.dss.x509.TimestampType;
import eu.europa.esig.dss.x509.crl.CRLToken;
import eu.europa.esig.dss.x509.crl.ListCRLSource;
import eu.europa.esig.dss.x509.crl.OfflineCRLSource;
import eu.europa.esig.dss.x509.ocsp.ListOCSPSource;
import eu.europa.esig.dss.x509.ocsp.OCSPToken;
import eu.europa.esig.dss.x509.ocsp.OfflineOCSPSource;

public abstract class DefaultAdvancedSignature implements AdvancedSignature {

	private static final Logger LOG = LoggerFactory.getLogger(DefaultAdvancedSignature.class);

	/**
	 * This is the reference to the global (external) pool of certificates. All encapsulated certificates in the signature are added to this pool. See
	 * {@link eu.europa.esig.dss.x509.CertificatePool}
	 */
	protected final CertificatePool certPool;

	/**
	 * In the case of a non AdES signature the signing certificate is not mandatory within the signature and can be provided by the driving application.
	 */
	protected CertificateToken providedSigningCertificateToken;

	/**
	 * In case of a detached signature this is the signed document.
	 */
	protected List<DSSDocument> detachedContents;

	/**
	 * This variable contains a list of reference validations (reference tag for
	 * XAdES or message-digest for CAdES)
	 */
	protected List<ReferenceValidation> referenceValidations;

	/**
	 * This variable contains the result of the signature mathematical validation. It is initialised when the method
	 * {@code checkSignatureIntegrity} is called.
	 */
	protected SignatureCryptographicVerification signatureCryptographicVerification;

	protected String structureValidation;

	/**
	 * The reference to the object containing all candidates to the signing certificate.
	 */
	protected CandidatesForSigningCertificate candidatesForSigningCertificate;

	// Enclosed content timestamps.
	protected List<TimestampToken> contentTimestamps;

	// Enclosed signature timestamps.
	protected transient List<TimestampToken> signatureTimestamps;

	// Enclosed SignAndRefs timestamps.
	protected List<TimestampToken> sigAndRefsTimestamps;

	// Enclosed RefsOnly timestamps.
	protected List<TimestampToken> refsOnlyTimestamps;

	// This variable contains the list of enclosed archive signature timestamps.
	protected List<TimestampToken> archiveTimestamps;

	// Cached {@code OfflineCRLSource}
	protected OfflineCRLSource offlineCRLSource;

	// Cached {@code OfflineOCSPSource}
	protected OfflineOCSPSource offlineOCSPSource;

	private AdvancedSignature masterSignature;

	protected SignaturePolicy signaturePolicy;

	/**
	 * This list represents all digest algorithms used to calculate the digest values of certificates.
	 */
	protected Set<DigestAlgorithm> usedCertificatesDigestAlgorithms = new HashSet<DigestAlgorithm>();

	private List<SignatureScope> signatureScopes;

	private String signatureFilename;

	/**
	 * @param certPool
	 *            can be null
	 */
	protected DefaultAdvancedSignature(final CertificatePool certPool) {
		this.certPool = certPool;
	}

	@Override
	public String getSignatureFilename() {
		return signatureFilename;
	}

	@Override
	public void setSignatureFilename(String signatureFilename) {
		this.signatureFilename = signatureFilename;
	}

	@Override
	public List<DSSDocument> getDetachedContents() {
		return detachedContents;
	}

	@Override
	public void setDetachedContents(final List<DSSDocument> detachedContents) {
		this.detachedContents = detachedContents;
	}

	/**
	 * @return the upper level for which data have been found. Doesn't mean any validity of the data found. Null if
	 *         unknown.
	 */
	@Override
	public SignatureLevel getDataFoundUpToLevel() {

		final SignatureLevel[] signatureLevels = getSignatureLevels();
		final SignatureLevel dataFoundUpToProfile = getDataFoundUpToProfile(signatureLevels);
		return dataFoundUpToProfile;
	}

	/**
	 * This method returns the {@code SignatureLevel} which was reached.
	 *
	 * @param signatureLevels
	 *            the array of the all levels associated with the given signature type
	 * @return {@code SignatureLevel}
	 */
	private SignatureLevel getDataFoundUpToProfile(final SignatureLevel... signatureLevels) {

		for (int ii = signatureLevels.length - 1; ii >= 0; ii--) {

			final SignatureLevel signatureLevel = signatureLevels[ii];
			if (isDataForSignatureLevelPresent(signatureLevel)) {
				return signatureLevel;
			}
		}
		return null;
	}

	/**
	 * This method validates the signing certificate and all timestamps.
	 *
	 * @return signature validation context containing all certificates and
	 *         revocation data used during the validation process.
	 */
	public ValidationContext getSignatureValidationContext(final CertificateVerifier certificateVerifier) {

		final ValidationContext validationContext = new SignatureValidationContext(certPool);
		certificateVerifier.setSignatureCRLSource(new ListCRLSource(getCRLSource()));
		certificateVerifier.setSignatureOCSPSource(new ListOCSPSource(getOCSPSource()));
		validationContext.initialize(certificateVerifier);

		final List<CertificateToken> certificates = getCertificates();
		for (final CertificateToken certificate : certificates) {
			validationContext.addCertificateTokenForVerification(certificate);
		}
		prepareTimestamps(validationContext);
		validationContext.validate();

		validateTimestamps();

		checkTimestamp(certificateVerifier, validationContext);
		checkAllRevocationDataPresent(certificateVerifier, validationContext);
		checkAllTimestampCoveredByRevocationData(certificateVerifier, validationContext);
		checkAllCertificateNotRevoked(certificateVerifier, validationContext);

		return validationContext;
	}

	private void checkAllCertificateNotRevoked(final CertificateVerifier certificateVerifier, final ValidationContext validationContext) {
		if (!validationContext.isAllCertificateValid()) {
			String message = "Revoked certificate detected";
			if (certificateVerifier.isExceptionOnRevokedCertificate()) {
				throw new DSSException(message);
			} else {
				LOG.warn(message);
			}
		}
	}

	private void checkAllTimestampCoveredByRevocationData(final CertificateVerifier certificateVerifier, final ValidationContext validationContext) {
		if (!validationContext.isAllPOECoveredByRevocationData()) {
			String message = "A POE is not covered by an usable revocation data";
			if (certificateVerifier.isExceptionOnUncoveredPOE()) {
				throw new DSSException(message);
			} else {
				LOG.warn(message);
			}
		}
	}

	private void checkAllRevocationDataPresent(final CertificateVerifier certificateVerifier, final ValidationContext validationContext) {
		if (!validationContext.isAllRequiredRevocationDataPresent()) {
			String message = "Revocation data is missing";
			if (certificateVerifier.isExceptionOnMissingRevocationData()) {
				throw new DSSException(message);
			} else {
				LOG.warn(message);
			}
		}
	}

	private void checkTimestamp(final CertificateVerifier certificateVerifier, final ValidationContext validationContext) {
		if (!validationContext.isAllTimestampValid()) {
			String message = "Broken timestamp detected";
			if (certificateVerifier.isExceptionOnInvalidTimestamp()) {
				throw new DSSException(message);
			} else {
				LOG.warn(message);
			}
		}
	}

	/**
	 * Returns an unmodifiable list of all certificate tokens encapsulated in the
	 * signature
	 * 
	 * @see eu.europa.esig.dss.validation.AdvancedSignature#getCertificates()
	 */
	@Override
	public List<CertificateToken> getCertificates() {
		return getCertificateSource().getCertificates();
	}

	/**
	 * This method returns all certificates used during the validation process. If a certificate is already present
	 * within the signature then it is ignored.
	 *
	 * @param validationContext
	 *            validation context containing all information about the validation process of the signing certificate
	 *            and time-stamps
	 * @return set of certificates not yet present within the signature
	 */
	public Set<CertificateToken> getCertificatesForInclusion(final ValidationContext validationContext) {

		final Set<CertificateToken> certificates = new HashSet<CertificateToken>();
		final List<CertificateToken> certWithinSignatures = getCertificatesWithinSignatureAndTimestamps();
		for (final CertificateToken certificateToken : validationContext.getProcessedCertificates()) {
			if (certWithinSignatures.contains(certificateToken)) {
				continue;
			}
			certificates.add(certificateToken);
		}
		return certificates;
	}

	public List<CertificateToken> getCertificatesWithinSignatureAndTimestamps() {
		List<CertificateToken> certs = new ArrayList<CertificateToken>();
		Map<String, List<CertificateToken>> certificatesWithinSignatureAndTimestamps = getCertificatesWithinSignatureAndTimestamps(false);
		for (List<CertificateToken> certificateTokens : certificatesWithinSignatureAndTimestamps.values()) {
			certs.addAll(certificateTokens);
		}
		return certs;
	}

	public Map<String, List<CertificateToken>> getCertificatesWithinSignatureAndTimestamps(boolean skipLastArchiveTimestamp) {
		Map<String, List<CertificateToken>> certificates = new HashMap<String, List<CertificateToken>>();
		// We can have more than one chain in the signature : signing certificate, ocsp
		// responder,...
		List<CertificateToken> certificatesSig = getCertificateSource().getCertificates();
		if (Utils.isCollectionNotEmpty(certificatesSig)) {
			certificates.put(CertificateSourceType.SIGNATURE.name(), certificatesSig);
		}
		int timestampCounter = 0;
		for (final TimestampToken timestampToken : getContentTimestamps()) {
			certificates.put(timestampToken.getTimeStampType().name() + timestampCounter++, timestampToken.getCertificates());
		}
		for (final TimestampToken timestampToken : getTimestampsX1()) {
			certificates.put(timestampToken.getTimeStampType().name() + timestampCounter++, timestampToken.getCertificates());
		}
		for (final TimestampToken timestampToken : getTimestampsX2()) {
			certificates.put(timestampToken.getTimeStampType().name() + timestampCounter++, timestampToken.getCertificates());
		}
		for (final TimestampToken timestampToken : getSignatureTimestamps()) {
			certificates.put(timestampToken.getTimeStampType().name() + timestampCounter++, timestampToken.getCertificates());
		}

		List<TimestampToken> archiveTsps = getArchiveTimestamps();
		if (skipLastArchiveTimestamp) {
			archiveTsps = removeLastTimestamp(archiveTsps);
		}
		for (final TimestampToken timestampToken : archiveTsps) {
			certificates.put(timestampToken.getTimeStampType().name() + timestampCounter++, timestampToken.getCertificates());
		}

		return certificates;
	}

	private List<TimestampToken> removeLastTimestamp(List<TimestampToken> timestamps) {
		List<TimestampToken> tsps = new ArrayList<TimestampToken>();
		Collections.copy(timestamps, tsps);
		if (Utils.collectionSize(tsps) > 1) {
			Collections.sort(tsps, new TimestampByGenerationTimeComparator());
			tsps.remove(tsps.size() - 1);
		}
		return tsps;
	}

	/**
	 * This method returns revocation values (ocsp and crl) that will be included in the LT profile.
	 *
	 * @param validationContext
	 *            {@code ValidationContext} contains all the revocation data retrieved during the validation process.
	 * @return {@code RevocationDataForInclusion}
	 */
	public RevocationDataForInclusion getRevocationDataForInclusion(final ValidationContext validationContext) {
		// TODO: to be checked: there can be also CRL and OCSP in TimestampToken CMS data
		final Set<RevocationToken> revocationTokens = validationContext.getProcessedRevocations();
		final List<CRLToken> crlTokens = new ArrayList<CRLToken>();
		final List<OCSPToken> ocspTokens = new ArrayList<OCSPToken>();
		final List<TokenIdentifier> revocationIds = new ArrayList<TokenIdentifier>(); // revocation equals : TokenId + certId + date
		for (final RevocationToken revocationToken : revocationTokens) {
			if (!revocationIds.contains(revocationToken.getDSSId())) {
				revocationIds.add(revocationToken.getDSSId());
				if (revocationToken instanceof CRLToken) {
					final CRLToken crlToken = (CRLToken) revocationToken;
					crlTokens.add(crlToken);
				} else if (revocationToken instanceof OCSPToken) {
					final OCSPToken ocspToken = (OCSPToken) revocationToken;
					ocspTokens.add(ocspToken);
				} else {
					throw new DSSException("Unknown type for revocationToken: " + revocationToken.getClass().getName());
				}
			}
		}
		return new RevocationDataForInclusion(crlTokens, ocspTokens);
	}

	@Override
	public void setMasterSignature(final AdvancedSignature masterSignature) {
		this.masterSignature = masterSignature;
	}

	@Override
	public AdvancedSignature getMasterSignature() {
		return masterSignature;
	}

	@Override
	public SignatureCryptographicVerification getSignatureCryptographicVerification() {
		if (signatureCryptographicVerification == null) {
			checkSignatureIntegrity();
		}
		return signatureCryptographicVerification;
	}

	public static class RevocationDataForInclusion {

		public final List<CRLToken> crlTokens;
		public final List<OCSPToken> ocspTokens;

		public RevocationDataForInclusion(final List<CRLToken> crlTokens, final List<OCSPToken> ocspTokens) {

			this.crlTokens = crlTokens;
			this.ocspTokens = ocspTokens;
		}

		public boolean isEmpty() {

			return crlTokens.isEmpty() && ocspTokens.isEmpty();
		}
	}

	@Override
	public CertificateToken getProvidedSigningCertificateToken() {
		return providedSigningCertificateToken;
	}

	@Override
	public void setProvidedSigningCertificateToken(final CertificateToken certificateToken) {
		this.providedSigningCertificateToken = certificateToken;
	}

	@Override
	public CertificateToken getSigningCertificateToken() {

		// This ensures that the variable candidatesForSigningCertificate has been initialized
		candidatesForSigningCertificate = getCandidatesForSigningCertificate();
		// This ensures that the variable signatureCryptographicVerification has been initialized
		checkSignatureIntegrity();
		signatureCryptographicVerification = getSignatureCryptographicVerification();
		final CertificateValidity theCertificateValidity = candidatesForSigningCertificate.getTheCertificateValidity();
		if (theCertificateValidity != null) {
			if (theCertificateValidity.isValid()) {
				final CertificateToken signingCertificateToken = theCertificateValidity.getCertificateToken();
				return signingCertificateToken;
			}
		}
		final CertificateValidity theBestCandidate = candidatesForSigningCertificate.getTheBestCandidate();
		return theBestCandidate == null ? null : theBestCandidate.getCertificateToken();
	}

	/**
	 * This method adds to the {@code ValidationContext} all timestamps to be validated.
	 *
	 * @param validationContext
	 *            {@code ValidationContext} to which the timestamps must be added
	 */
	@Override
	public void prepareTimestamps(final ValidationContext validationContext) {

		/*
		 * This validates the signature timestamp tokens present in the signature.
		 */
		for (final TimestampToken timestampToken : getContentTimestamps()) {
			validationContext.addTimestampTokenForVerification(timestampToken);
		}

		/*
		 * This validates the signature timestamp tokens present in the signature.
		 */
		for (final TimestampToken timestampToken : getSignatureTimestamps()) {
			validationContext.addTimestampTokenForVerification(timestampToken);
		}

		/*
		 * This validates the SigAndRefs timestamp tokens present in the signature.
		 */
		for (final TimestampToken timestampToken : getTimestampsX1()) {
			validationContext.addTimestampTokenForVerification(timestampToken);
		}

		/*
		 * This validates the RefsOnly timestamp tokens present in the signature.
		 */
		for (final TimestampToken timestampToken : getTimestampsX2()) {
			validationContext.addTimestampTokenForVerification(timestampToken);
		}

		/*
		 * This validates the archive timestamp tokens present in the signature.
		 */
		for (final TimestampToken timestampToken : getArchiveTimestamps()) {
			validationContext.addTimestampTokenForVerification(timestampToken);
		}
	}

	/**
	 * This method adds all timestamps to be validated.
	 */
	@Override
	public void validateTimestamps() {

		/*
		 * This validates the content-timestamp tokensToProcess present in the signature.
		 */
		for (final TimestampToken timestampToken : getContentTimestamps()) {
			final byte[] timestampBytes = getContentTimestampData(timestampToken);
			timestampToken.matchData(timestampBytes);
		}

		/*
		 * This validates the signature timestamp tokensToProcess present in the signature.
		 */
		for (final TimestampToken timestampToken : getSignatureTimestamps()) {
			final byte[] timestampBytes = getSignatureTimestampData(timestampToken, null);
			timestampToken.matchData(timestampBytes);
		}

		/*
		 * This validates the SigAndRefs timestamp tokensToProcess present in the signature.
		 */
		for (final TimestampToken timestampToken : getTimestampsX1()) {
			final byte[] timestampBytes = getTimestampX1Data(timestampToken, null);
			timestampToken.matchData(timestampBytes);
		}

		/*
		 * This validates the RefsOnly timestamp tokensToProcess present in the signature.
		 */
		for (final TimestampToken timestampToken : getTimestampsX2()) {
			final byte[] timestampBytes = getTimestampX2Data(timestampToken, null);
			timestampToken.matchData(timestampBytes);
		}

		/*
		 * This validates the archive timestamp tokensToProcess present in the signature.
		 */
		for (final TimestampToken timestampToken : getArchiveTimestamps()) {
			if (!timestampToken.isProcessed()) {
				final byte[] timestampData = getArchiveTimestampData(timestampToken, null);
				timestampToken.matchData(timestampData);
			}
		}
	}

	@Override
	public void validateStructure() {
	}

	@Override
	public String getStructureValidationResult() {
		return structureValidation;
	}

	/**
	 * This method adds references to retrieved OCSP responses from LT level. With LTA level, we have a proof of
	 * existence
	 * 
	 * @param references
	 */
	protected void addReferencesFromOfflineOCSPSource(List<TimestampReference> references) {
		OfflineOCSPSource ocspSource = getOCSPSource();
		if (ocspSource != null) {
			List<BasicOCSPResp> containedOCSPResponses = ocspSource.getContainedOCSPResponses();
			if (Utils.isCollectionNotEmpty(containedOCSPResponses)) {
				usedCertificatesDigestAlgorithms.add(DigestAlgorithm.SHA1);
				for (BasicOCSPResp basicOCSPResp : containedOCSPResponses) {
					OCSPResp ocspResp = DSSRevocationUtils.fromBasicToResp(basicOCSPResp);
					final byte[] digest = DSSUtils.digest(DigestAlgorithm.SHA1, DSSRevocationUtils.getEncoded(ocspResp));
					references.add(new TimestampReference(DigestAlgorithm.SHA1, Utils.toBase64(digest), TimestampedObjectType.REVOCATION));
				}
			}
		}
	}

	/**
	 * This method adds references to retrieved CRL responses from LT level. With LTA level, we have a proof of
	 * existence
	 * 
	 * @param references
	 */
	protected void addReferencesFromOfflineCRLSource(List<TimestampReference> references) {
		OfflineCRLSource crlSource = getCRLSource();
		if (crlSource != null) {
			Collection<byte[]> containedX509CRLs = crlSource.getContainedX509CRLs();
			if (Utils.isCollectionNotEmpty(containedX509CRLs)) {
				usedCertificatesDigestAlgorithms.add(DigestAlgorithm.SHA1);
				for (byte[] x509crl : containedX509CRLs) {
					final byte[] digest = DSSUtils.digest(DigestAlgorithm.SHA1, x509crl);
					references.add(new TimestampReference(DigestAlgorithm.SHA1, Utils.toBase64(digest), TimestampedObjectType.REVOCATION));
				}
			}
		}
	}

	@Override
	public Set<DigestAlgorithm> getUsedCertificatesDigestAlgorithms() {
		return usedCertificatesDigestAlgorithms;
	}

	@Override
	public SignaturePolicy getPolicyId() {
		return signaturePolicy;
	}

	@Override
	public void checkSignaturePolicy(SignaturePolicyProvider signaturePolicyDetector) {
	}

	@Override
	public void findSignatureScope(SignatureScopeFinder signatureScopeFinder) {
		signatureScopes = signatureScopeFinder.findSignatureScope(this);
	}

	@Override
	public List<SignatureScope> getSignatureScopes() {
		return signatureScopes;
	}

	@Override
	public void addExternalTimestamp(TimestampToken timestamp) {
		if (!timestamp.isProcessed()) {
			throw new DSSException("Timestamp token must be validated first !");
		}

		if (TimestampType.ARCHIVE_TIMESTAMP != timestamp.getTimeStampType()) {
			throw new DSSException("Only archival timestamp is allowed !");
		}

		if (archiveTimestamps == null) {
			archiveTimestamps = new ArrayList<TimestampToken>();
		}
		archiveTimestamps.add(timestamp);
	}

	/* Defines the level T */
	public boolean hasTProfile() {
		return Utils.isCollectionNotEmpty(getSignatureTimestamps());
	}

	/* Defines the level LT */
	public boolean hasLTProfile() {
		Map<String, List<CertificateToken>> certificateChains = getCertificatesWithinSignatureAndTimestamps(true);
		
		boolean emptyOCSPs = Utils.isCollectionEmpty(getOCSPSource().getContainedOCSPResponses());
		boolean emptyCRLs = Utils.isCollectionEmpty(getCRLSource().getContainedX509CRLs());

		if (certificateChains.isEmpty() && (emptyOCSPs || emptyCRLs)) {
			return false;
		}

		if (!isAllCertChainsHaveRevocationData(certificateChains)) {
			return false;
		}

		if (isAllSelfSignedCertificates(certificateChains) && (emptyOCSPs && emptyCRLs)) {
			return false;
		}

		return true;
	}

	private boolean isAllSelfSignedCertificates(Map<String, List<CertificateToken>> certificateChains) {
		for (Entry<String, List<CertificateToken>> entryCertChain : certificateChains.entrySet()) {
			List<CertificateToken> chain = entryCertChain.getValue();
			if (Utils.collectionSize(chain) == 1) {
				CertificateToken certificateToken = chain.get(0);
				if (!certificateToken.isSelfSigned()) {
					return false;
				}
			} else {
				return false;
			}
		}
		return true;
	}

	private boolean isAllCertChainsHaveRevocationData(Map<String, List<CertificateToken>> certificateChains) {
		CertificateStatusVerifier certificateStatusVerifier = new OCSPAndCRLCertificateVerifier(getCRLSource(), getOCSPSource(), certPool);

		for (Entry<String, List<CertificateToken>> entryCertChain : certificateChains.entrySet()) {
			LOG.debug("Testing revocation data presence for certificates chain {}", entryCertChain.getKey());
			if (!isAllCertsHaveRevocationData(certificateStatusVerifier, entryCertChain.getValue())) {
				LOG.debug("Revocation data missing in certificate chain {}", entryCertChain.getKey());
				return false;
			}
		}
		return true;
	}

	private boolean isAllCertsHaveRevocationData(CertificateStatusVerifier certificateStatusVerifier, List<CertificateToken> certificates) {
		// we reorder the certificate list, the order is not guaranteed
		Map<CertificateToken, List<CertificateToken>> orderedCerts = order(certificates);
		for (List<CertificateToken> chain : orderedCerts.values()) {
			for (CertificateToken certificateToken : chain) {
				if (!isRevocationRequired(certificateToken)) {
					// Skip this loop to avoid checking upper levels than trusted certificates
					// (cross certification)
					break;
				}
				RevocationToken revocationData = certificateStatusVerifier.check(certificateToken);
				if (revocationData == null) {
					return false;
				}
			}
		}
		return true;
	}

	private Map<CertificateToken, List<CertificateToken>> order(List<CertificateToken> certificates) {
		CertificateReorderer reorderer = new CertificateReorderer(certificates);
		return reorderer.getOrderedCertificateChains();
	}

	private boolean isRevocationRequired(CertificateToken certificateToken) {
		if (certPool.isTrusted(certificateToken) || certificateToken.isSelfSigned()) {
			return false;
		}

		return !DSSASN1Utils.hasIdPkixOcspNoCheckExtension(certificateToken);
	}

	/* Defines the level LTA */
	public boolean hasLTAProfile() {
		return Utils.isCollectionNotEmpty(getArchiveTimestamps());
	}

}
