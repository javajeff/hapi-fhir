package ca.uhn.fhir.jpa.validation;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.model.ElementDefinition;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.model.ValueSet;
import org.hl7.fhir.r5.utils.validation.IResourceValidator;
import org.hl7.fhir.r5.utils.validation.IValidationPolicyAdvisor;
import org.hl7.fhir.r5.utils.validation.constants.BindingKind;
import org.hl7.fhir.r5.utils.validation.constants.CodedContentValidationPolicy;
import org.hl7.fhir.r5.utils.validation.constants.ContainedReferenceValidationPolicy;
import org.hl7.fhir.r5.utils.validation.constants.ReferenceValidationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

public class ValidatorPolicyAdvisor implements IValidationPolicyAdvisor {

	private static final Logger ourLog = LoggerFactory.getLogger(ValidatorPolicyAdvisor.class);

	@Autowired
	private ValidationSettings myValidationSettings;
	@Autowired
	private FhirContext myFhirContext;

	@Override
	public ReferenceValidationPolicy policyForReference(IResourceValidator validator, Object appContext, String path, String url) {
		int slashIdx = url.indexOf("/");
		if (slashIdx > 0 && myFhirContext.getResourceTypes().contains(url.substring(0, slashIdx))) {
			return myValidationSettings.getLocalReferenceValidationDefaultPolicy();
		}

		return ReferenceValidationPolicy.IGNORE;
	}

	@Override
	public CodedContentValidationPolicy policyForCodedContent(IResourceValidator iResourceValidator, Object o, String s, ElementDefinition elementDefinition, StructureDefinition structureDefinition, BindingKind bindingKind, ValueSet valueSet, List<String> list) {
		return CodedContentValidationPolicy.CODE;
	}

	@Override
	public ContainedReferenceValidationPolicy policyForContained(IResourceValidator validator, Object appContext, String containerType, String containerId, Element.SpecialElement containingResourceType, String path, String url) {
		return ContainedReferenceValidationPolicy.CHECK_VALID;
	}
}