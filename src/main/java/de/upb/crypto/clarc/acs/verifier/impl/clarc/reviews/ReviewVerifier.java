package de.upb.crypto.clarc.acs.verifier.impl.clarc.reviews;

import de.upb.crypto.clarc.acs.issuer.impl.clarc.reviewtokens.ReviewToken;
import de.upb.crypto.clarc.acs.issuer.impl.clarc.reviewtokens.ReviewTokenIssuerPublicIdentity;
import de.upb.crypto.clarc.acs.protocols.impl.clarc.RateVerifyProtocolFactory;
import de.upb.crypto.clarc.acs.review.impl.clarc.Review;
import de.upb.crypto.clarc.acs.setup.impl.clarc.PublicParameters;
import de.upb.crypto.clarc.acs.setup.impl.clarc.PublicParametersFactory;
import de.upb.crypto.clarc.acs.systemmanager.impl.clarc.SystemManager;
import de.upb.crypto.clarc.acs.systemmanager.impl.clarc.SystemManagerPublicIdentity;
import de.upb.crypto.clarc.protocols.fiatshamirtechnique.FiatShamirSignatureScheme;
import de.upb.crypto.clarc.protocols.fiatshamirtechnique.impl.FiatShamirVerificationKey;
import de.upb.crypto.clarc.protocols.generalizedschnorrprotocol.GeneralizedSchnorrProtocol;
import de.upb.crypto.clarc.protocols.generalizedschnorrprotocol.GeneralizedSchnorrProtocolProvider;
import de.upb.crypto.craco.sig.ps.PSExtendedSignatureScheme;
import de.upb.crypto.craco.sig.ps.PSExtendedVerificationKey;
import de.upb.crypto.math.hash.impl.SHA256HashFunction;
import de.upb.crypto.math.interfaces.mappings.BilinearMap;
import de.upb.crypto.math.interfaces.structures.GroupElement;

import static de.upb.crypto.clarc.acs.protocols.impl.clarc.ComputeRatingPublicKeyAndItemHashHelper.getHashedRatingPublicKeyAndItem;

/**
 * Implementation the actor responsible for the issuing of ratings and also for the linking and verifying of ratings.
 */
public class ReviewVerifier implements de.upb.crypto.clarc.acs.verifier.reviews.ReviewVerifier {
    private PublicParameters pp;
    private SystemManagerPublicIdentity systemManagerPublicIdentity;

    private PSExtendedVerificationKey raterPublicKey;

    /**
     * Initializes The ReviewVerifier with the linkability basis from the SystemManager
     * {@link SystemManager}.
     *
     * @param pp                              The acs public parameters
     * @param systemManagerPublicIdentity     The public identity of the system manager which contains the linkability
     *                                        information used for linking two reviews
     * @param reviewTokenIssuerPublicIdentity Public identity of the token issuer
     */
    public ReviewVerifier(PublicParameters pp,
                          SystemManagerPublicIdentity systemManagerPublicIdentity,
                          ReviewTokenIssuerPublicIdentity reviewTokenIssuerPublicIdentity) {
        this.pp = pp;
        this.systemManagerPublicIdentity = systemManagerPublicIdentity;

        PSExtendedSignatureScheme signatureScheme = PublicParametersFactory.getSignatureScheme(pp);
        this.raterPublicKey = signatureScheme.getVerificationKey(reviewTokenIssuerPublicIdentity.getIssuerPublicKey());
    }

    /**
     * This method checks if two reviews are from the same user, so it tries to "link" two different reviews to a
     * single user.
     *
     * @param review1 First review
     * @param review2 Second review
     * @return Returns whether the reviews are from the same user or not.
     */
    @Override
    public boolean areFromSameUser(de.upb.crypto.clarc.acs.review.Review review1, de.upb.crypto.clarc.acs.review.Review review2) {
        if (!verify(review1) || !verify(review2)) {
            return false;
        }
        Review firstReview = (Review) review1;
        Review secondReview = (Review) review2;

        GroupElement L1 = firstReview.getL1();
        GroupElement L2 = firstReview.getL2();

        GroupElement L1star = secondReview.getL1();
        GroupElement L2star = secondReview.getL2();

        BilinearMap map = pp.getBilinearMap();
        GroupElement leftSide = map.apply(L1.op(L1star.inv()), systemManagerPublicIdentity.getLinkabilityBasis());

        ReviewToken token = new ReviewToken(
                ((Review) review1).getBlindedTokenSignature(),
                ((Review) review1).getItem(),
                raterPublicKey);

        GroupElement hash = getHashedRatingPublicKeyAndItem(token, pp);

        GroupElement rightSide = map.apply(hash, L2.op(L2star.inv()));
        return leftSide.equals(rightSide);
    }

    /**
     * This method hecks if the signature within the review is valid by using the {@link FiatShamirSignatureScheme}
     * and the {@link RateVerifyProtocolFactory}.
     *
     * @param review The review to check
     * @return whether the signature is valid or not
     */
    @Override
    public boolean verify(de.upb.crypto.clarc.acs.review.Review review) {
        if (!(review instanceof Review)) {
            throw new IllegalArgumentException("Expected Review");
        }
        Review clarcReview = (Review) review;

        ReviewToken token = new ReviewToken(clarcReview.getBlindedTokenSignature(),
                clarcReview.getItem(),
                clarcReview.getRaterPublicKey());
        RateVerifyProtocolFactory factory = new RateVerifyProtocolFactory(pp,
                clarcReview.getBlindedRegistrationInformation(),
                clarcReview.getSystemManagerPublicKey(),
                systemManagerPublicIdentity.getLinkabilityBasis(),
                token,
                clarcReview.getL1(),
                clarcReview.getL2());
        GeneralizedSchnorrProtocol rateVerifyProtocol = factory.getProtocol();
        GeneralizedSchnorrProtocolProvider protocolProvider = new GeneralizedSchnorrProtocolProvider(pp.getZp());
        FiatShamirSignatureScheme signatureScheme =
                new FiatShamirSignatureScheme(protocolProvider, new SHA256HashFunction());
        FiatShamirVerificationKey verificationKey = new FiatShamirVerificationKey(rateVerifyProtocol.getProblems());
        return signatureScheme.verify(clarcReview.getMessage(), clarcReview.getRatingSignature(), verificationKey);
    }
}
