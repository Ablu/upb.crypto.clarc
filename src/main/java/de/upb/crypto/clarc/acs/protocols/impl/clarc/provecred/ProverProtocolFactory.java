package de.upb.crypto.clarc.acs.protocols.impl.clarc.provecred;

import de.upb.crypto.clarc.acs.attributes.AttributeSpace;
import de.upb.crypto.clarc.acs.pseudonym.Pseudonym;
import de.upb.crypto.clarc.acs.setup.impl.clarc.PublicParameters;
import de.upb.crypto.clarc.acs.user.credentials.PSCredential;
import de.upb.crypto.clarc.acs.user.impl.clarc.UserSecret;
import de.upb.crypto.clarc.predicategeneration.fixedprotocols.popk.ProofOfPartialKnowledgeProtocol;
import de.upb.crypto.clarc.predicategeneration.fixedprotocols.popk.ProofOfPartialKnowledgePublicParameters;
import de.upb.crypto.clarc.predicategeneration.policies.SigmaProtocolPolicyFact;
import de.upb.crypto.clarc.predicategeneration.policies.SubPolicyPolicyFact;
import de.upb.crypto.clarc.protocols.arguments.SigmaProtocol;
import de.upb.crypto.craco.commitment.pedersen.PedersenOpenValue;
import de.upb.crypto.craco.interfaces.policy.Policy;
import de.upb.crypto.craco.interfaces.policy.ThresholdPolicy;
import de.upb.crypto.math.structures.zn.Zp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Factory for generating a prover protocol to prove fulfillment of a {@link Policy} utilizing the owned
 * {@link PSCredential}
 */
public class ProverProtocolFactory extends ProtocolFactory {
    private final PSCredential[] credentials;
    private final PedersenOpenValue pseudonymSecret;
    protected final UserSecret usk;


    /**
     * Instantiates a {@link de.upb.crypto.clarc.acs.protocols.ProtocolFactory} with given public and protocol parameters.
     * It is able to generate a protocol to prove the fulfillment of a {@link Policy}.
     * The currently available attribute spaces are needed to correctly parse the policy to be fulfilled.
     * <p>
     * The order of the given {@link PSCredential} must match the order of
     * {@link ProtocolFactory#getSubPolicies} they should be used to prove fulfillment of.
     * If there is no {@link PSCredential} available for a certain sub policy
     * null is expected at the corresponding position in the array.
     * </p>
     *
     * @param protocolParameters shared input for all protocol instances (prover and verifier)
     * @param publicParameters   the system's public parameters
     * @param attributeSpaces    currently available attribute spaces
     * @param credentials        the credentials which are available for proving.
     * @param usk                the user secret
     * @param pseudonymSecret    the secret value of the {@link Pseudonym} used during the interaction with the verifier
     * @param policy             the policy of which the fulfillment is to be proven
     */
    public ProverProtocolFactory(ProtocolParameters protocolParameters,
                                 PublicParameters publicParameters,
                                 List<AttributeSpace> attributeSpaces,
                                 PSCredential[] credentials,
                                 UserSecret usk, PedersenOpenValue pseudonymSecret, Policy policy) {
        this(protocolParameters, publicParameters, attributeSpaces, credentials, usk, pseudonymSecret, policy, null);
    }

    /**
     * Instantiates a {@link de.upb.crypto.clarc.acs.protocols.ProtocolFactory} with given public and protocol parameters.
     * It is able to generate a protocol to prove the fulfillment of a {@link Policy}.
     * The currently available attribute spaces are needed to correctly parse the policy to be fulfilled.
     * <p>
     * The order of the given {@link PSCredential} and {@link SelectiveDisclosure} must match the order of
     * {@link ProtocolFactory#getSubPolicies} associated with the same issuer.
     * If there is no {@link PSCredential} or {@link SelectiveDisclosure} available for a certain sub policy
     * null is expected at the corresponding position in the arrays.
     * </p>
     *
     * @param protocolParameters shared input for all protocol instances (prover and verifier)
     * @param publicParameters   the system's public parameters
     * @param attributeSpaces    currently available attribute spaces
     * @param credentials        the credentials which are available for proving.
     * @param usk                the user secret
     * @param pseudonymSecret    the secret value of the {@link Pseudonym} used during the interaction with the verifier
     * @param policy             the policy of which the fulfillment is to be proven
     * @param disclosures        attributes to be disclosed
     */
    public ProverProtocolFactory(ProtocolParameters protocolParameters,
                                 PublicParameters publicParameters,
                                 List<AttributeSpace> attributeSpaces,
                                 PSCredential[] credentials,
                                 UserSecret usk, PedersenOpenValue pseudonymSecret, Policy policy,
                                 SelectiveDisclosure[] disclosures) {
        super(protocolParameters, publicParameters, attributeSpaces, policy, disclosures);
        // The super class already verifies the amount of disclosures, so we can test against that
        if (credentials == null || credentials.length != this.disclosures.length) {
            throw new IllegalArgumentException("The number of provided credentials does not match the number of" +
                    " sub policies");
        }
        this.credentials = credentials;
        this.usk = usk;
        this.pseudonymSecret = pseudonymSecret;
    }

    @Override
    public PolicyProvingProtocol getProtocol() {
        List<Witness> witnesses = new ArrayList<>();
        ThresholdPolicy transformedPolicy = transformPolicy(policy, witnesses, new AtomicInteger(0));

        List<DisclosedAttributes> disclosedAttributes =
                witnesses.stream()
                        .filter(witness -> !witness.getDisclosedElements().isEmpty())
                        .map(witness ->
                                new DisclosedAttributes(witness.getIssuerPublicKey(),
                                        new ArrayList<>(witness.getDisclosedElements().values())))
                        .collect(Collectors.toList());

        ProofOfPartialKnowledgePublicParameters popkPublicParameters =
                new ProofOfPartialKnowledgePublicParameters(protocolParameters.getLsssProvider(),
                        publicParameters.getZp());

        //Since the witnesses are already set by the internal protocols we can use the verifier constructor
        ProofOfPartialKnowledgeProtocol innerProtocol =
                new ProofOfPartialKnowledgeProtocol(popkPublicParameters, transformedPolicy);
        return new PolicyProvingProtocol(innerProtocol, disclosedAttributes);
    }

    /**
     * Transforms the given {@link ThresholdPolicy} with contains {@link SubPolicyPolicyFact} as leaves into a
     * {@link ThresholdPolicy} which contains {@link SigmaProtocolPolicyFact} as leaves as required by the
     * {@link ProofOfPartialKnowledgeProtocol}.
     * <br>
     * For this transformation {@link ProtocolFactory#createProtocolForSubpolicy} is called for every contained
     * sub policy.
     *
     * @param policy      the policy to transform
     * @param witnesses   list of witnesses created for the proof
     * @param leafCounter stateful counter to ensure unique ids provided to every created
     *                    {@link SigmaProtocolPolicyFact}
     * @return {@link ThresholdPolicy} which contains {@link SigmaProtocolPolicyFact} as leaves
     */
    private ThresholdPolicy transformPolicy(ThresholdPolicy policy, List<Witness> witnesses,
                                            AtomicInteger leafCounter) {
        List<Policy> children = policy.getChildren();
        List<Policy> transformedChildren = new ArrayList<>(children.size());
        for (Policy childPolicy : children) {
            if (childPolicy instanceof SubPolicyPolicyFact) {
                int leafId = leafCounter.getAndIncrement();
                SubPolicyPolicyFact subPolicy = (SubPolicyPolicyFact) childPolicy;
                PSCredential credential = credentials[leafId];
                Zp.ZpElement nymRandom = pseudonymSecret.getRandomValue();

                SelectiveDisclosure disclosure = (disclosures[leafId] != null) ? disclosures[leafId] :
                        new SelectiveDisclosure(subPolicy.getIssuerPublicKeyRepresentation(), Collections.emptyList());

                Witness witness = new Witness(credential, nymRandom, usk, leafId, disclosure);
                witnesses.add(witness);

                SigmaProtocol protocol = createProtocolForSubpolicy(subPolicy, witness);

                transformedChildren.add(new SigmaProtocolPolicyFact(protocol, leafId));
            } else if (childPolicy instanceof ThresholdPolicy) {
                transformedChildren.add(transformPolicy((ThresholdPolicy) childPolicy, witnesses, leafCounter));
            } else {
                throw new IllegalArgumentException("Malformed Policy!");
            }
        }
        return new ThresholdPolicy(policy.getThreshold(), transformedChildren);
    }

}
