package ca.uhn.fhir.jpa.dao.predicate;

/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2020 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.dao.SearchBuilder;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.BasePartitionable;
import ca.uhn.fhir.jpa.model.entity.BaseResourceIndexedSearchParam;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamDate;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.model.entity.SearchParamPresent;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.rest.param.ParamPrefixEnum;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

abstract class BasePredicateBuilder {
	private static final Logger ourLog = LoggerFactory.getLogger(BasePredicateBuilder.class);
	final CriteriaBuilder myCriteriaBuilder;
	final QueryRoot myQueryRoot;
	final Class<? extends IBaseResource> myResourceType;
	final String myResourceName;
	final SearchParameterMap myParams;
	@Autowired
	FhirContext myContext;
	@Autowired
	DaoConfig myDaoConfig;
	boolean myDontUseHashesForSearch;
	@Autowired
	private PartitionSettings myPartitionSettings;

	BasePredicateBuilder(SearchBuilder theSearchBuilder) {
		myCriteriaBuilder = theSearchBuilder.getBuilder();
		myQueryRoot = theSearchBuilder.getQueryRoot();
		myResourceType = theSearchBuilder.getResourceType();
		myResourceName = theSearchBuilder.getResourceName();
		myParams = theSearchBuilder.getParams();
	}

	@PostConstruct
	private void postConstruct() {
		myDontUseHashesForSearch = myDaoConfig.getDisableHashBasedSearches();
	}

	@SuppressWarnings("unchecked")
	<T> Join<ResourceTable, T> createJoin(SearchBuilderJoinEnum theType, String theSearchParameterName) {
		Join<ResourceTable, ResourceIndexedSearchParamDate> join = null;
		switch (theType) {
			case DATE:
				join = myQueryRoot.join("myParamsDate", JoinType.LEFT);
				break;
			case NUMBER:
				join = myQueryRoot.join("myParamsNumber", JoinType.LEFT);
				break;
			case QUANTITY:
				join = myQueryRoot.join("myParamsQuantity", JoinType.LEFT);
				break;
			case REFERENCE:
				join = myQueryRoot.join("myResourceLinks", JoinType.LEFT);
				break;
			case STRING:
				join = myQueryRoot.join("myParamsString", JoinType.LEFT);
				break;
			case URI:
				join = myQueryRoot.join("myParamsUri", JoinType.LEFT);
				break;
			case TOKEN:
				join = myQueryRoot.join("myParamsToken", JoinType.LEFT);
				break;
			case COORDS:
				join = myQueryRoot.join("myParamsCoords", JoinType.LEFT);
				break;
		}

		SearchBuilderJoinKey key = new SearchBuilderJoinKey(theSearchParameterName, theType);
		myQueryRoot.putIndex(key, join);

		return (Join<ResourceTable, T>) join;
	}

	void addPredicateParamMissingForReference(String theResourceName, String theParamName, boolean theMissing, RequestPartitionId theRequestPartitionId) {
		Join<ResourceTable, SearchParamPresent> paramPresentJoin = myQueryRoot.join("mySearchParamPresents", JoinType.LEFT);

		Expression<Long> hashPresence = paramPresentJoin.get("myHashPresence").as(Long.class);
		Long hash = SearchParamPresent.calculateHashPresence(myPartitionSettings, theRequestPartitionId, theResourceName, theParamName, !theMissing);

		List<Predicate> predicates = new ArrayList<>();
		predicates.add(myCriteriaBuilder.equal(hashPresence, hash));

		addPartitionIdPredicate(theRequestPartitionId, paramPresentJoin, predicates);

		myQueryRoot.setHasIndexJoins();
		myQueryRoot.addPredicates(predicates);
	}

	void addPredicateParamMissingForNonReference(String theResourceName, String theParamName, boolean theMissing, Join<ResourceTable, ? extends BaseResourceIndexedSearchParam> theJoin, RequestPartitionId theRequestPartitionId) {
		if (theRequestPartitionId != null) {
			if (theRequestPartitionId.getPartitionId() != null) {
				myQueryRoot.addPredicate(myCriteriaBuilder.equal(theJoin.get("myPartitionIdValue"), theRequestPartitionId.getPartitionId()));
			} else {
				myQueryRoot.addPredicate(myCriteriaBuilder.isNull(theJoin.get("myPartitionIdValue")));
			}
		}
		myQueryRoot.addPredicate(myCriteriaBuilder.equal(theJoin.get("myResourceType"), theResourceName));
		myQueryRoot.addPredicate(myCriteriaBuilder.equal(theJoin.get("myParamName"), theParamName));
		myQueryRoot.addPredicate(myCriteriaBuilder.equal(theJoin.get("myMissing"), theMissing));
		myQueryRoot.setHasIndexJoins();
	}

	Predicate combineParamIndexPredicateWithParamNamePredicate(String theResourceName, String theParamName, From<?, ? extends BaseResourceIndexedSearchParam> theFrom, Predicate thePredicate, RequestPartitionId theRequestPartitionId) {
		List<Predicate> andPredicates = new ArrayList<>();
		addPartitionIdPredicate(theRequestPartitionId, theFrom, andPredicates);

		if (myDontUseHashesForSearch) {
			Predicate resourceTypePredicate = myCriteriaBuilder.equal(theFrom.get("myResourceType"), theResourceName);
			Predicate paramNamePredicate = myCriteriaBuilder.equal(theFrom.get("myParamName"), theParamName);
			andPredicates.add(resourceTypePredicate);
			andPredicates.add(paramNamePredicate);
			andPredicates.add(thePredicate);
		} else {
			long hashIdentity = BaseResourceIndexedSearchParam.calculateHashIdentity(myPartitionSettings, theRequestPartitionId, theResourceName, theParamName);
			Predicate hashIdentityPredicate = myCriteriaBuilder.equal(theFrom.get("myHashIdentity"), hashIdentity);
			andPredicates.add(hashIdentityPredicate);
			andPredicates.add(thePredicate);
		}

		return myCriteriaBuilder.and(toArray(andPredicates));
	}

	public PartitionSettings getPartitionSettings() {
		return myPartitionSettings;
	}

	Predicate createPredicateNumeric(String theResourceName,
												String theParamName,
												From<?, ? extends BaseResourceIndexedSearchParam> theFrom,
												CriteriaBuilder builder,
												IQueryParameterType theParam,
												ParamPrefixEnum thePrefix,
												BigDecimal theValue,
												final Expression<BigDecimal> thePath,
												String invalidMessageName, RequestPartitionId theRequestPartitionId) {
		Predicate num;
		// Per discussions with Grahame Grieve and James Agnew on 11/13/19, modified logic for EQUAL and NOT_EQUAL operators below so as to
		//   use exact value matching.  The "fuzz amount" matching is still used with the APPROXIMATE operator.
		switch (thePrefix) {
			case GREATERTHAN:
				num = builder.gt(thePath, theValue);
				break;
			case GREATERTHAN_OR_EQUALS:
				num = builder.ge(thePath, theValue);
				break;
			case LESSTHAN:
				num = builder.lt(thePath, theValue);
				break;
			case LESSTHAN_OR_EQUALS:
				num = builder.le(thePath, theValue);
				break;
			case EQUAL:
				num = builder.equal(thePath, theValue);
				break;
			case NOT_EQUAL:
				num = builder.notEqual(thePath, theValue);
				break;
			case APPROXIMATE:
				BigDecimal mul = SearchFuzzUtil.calculateFuzzAmount(thePrefix, theValue);
				BigDecimal low = theValue.subtract(mul, MathContext.DECIMAL64);
				BigDecimal high = theValue.add(mul, MathContext.DECIMAL64);
				Predicate lowPred;
				Predicate highPred;
				lowPred = builder.ge(thePath.as(BigDecimal.class), low);
				highPred = builder.le(thePath.as(BigDecimal.class), high);
				num = builder.and(lowPred, highPred);
				ourLog.trace("Searching for {} <= val <= {}", low, high);
				break;
			case ENDS_BEFORE:
			case STARTS_AFTER:
			default:
				String msg = myContext.getLocalizer().getMessage(SearchBuilder.class, invalidMessageName, thePrefix.getValue(), theParam.getValueAsQueryToken(myContext));
				throw new InvalidRequestException(msg);
		}

		if (theParamName == null) {
			return num;
		}
		return combineParamIndexPredicateWithParamNamePredicate(theResourceName, theParamName, theFrom, num, theRequestPartitionId);
	}

	void addPartitionIdPredicate(RequestPartitionId theRequestPartitionId, From<?, ? extends BasePartitionable> theJoin, List<Predicate> theCodePredicates) {
		if (theRequestPartitionId != null) {
			Integer partitionId = theRequestPartitionId.getPartitionId();
			Predicate partitionPredicate;
			if (partitionId != null) {
				partitionPredicate = myCriteriaBuilder.equal(theJoin.get("myPartitionIdValue").as(Integer.class), partitionId);
			} else {
				partitionPredicate = myCriteriaBuilder.isNull(theJoin.get("myPartitionIdValue").as(Integer.class));
			}
			myQueryRoot.addPredicate(partitionPredicate);
		}
	}

	static String createLeftAndRightMatchLikeExpression(String likeExpression) {
		return "%" + likeExpression.replace("%", "[%]") + "%";
	}

	static String createLeftMatchLikeExpression(String likeExpression) {
		return likeExpression.replace("%", "[%]") + "%";
	}

	static String createRightMatchLikeExpression(String likeExpression) {
		return "%" + likeExpression.replace("%", "[%]");
	}

	static Predicate[] toArray(List<Predicate> thePredicates) {
		return thePredicates.toArray(new Predicate[0]);
	}
}
