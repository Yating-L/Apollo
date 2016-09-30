package org.bbop.apollo

import grails.converters.JSON
import grails.transaction.NotTransactional
import grails.transaction.Transactional
import org.bbop.apollo.gwt.shared.FeatureStringEnum
import org.bbop.apollo.projection.Coordinate
import org.bbop.apollo.projection.DiscontinuousProjection
import org.bbop.apollo.projection.Location
import org.bbop.apollo.projection.MultiSequenceProjection
import org.bbop.apollo.projection.ProjectionSequence
import org.codehaus.groovy.grails.web.json.JSONArray
import org.codehaus.groovy.grails.web.json.JSONObject

@Transactional
class AssemblageService {

    def permissionService
    def preferenceService
    def featureProjectionService
    def projectionService

    /**
     * Gets the unique feature locations from the feature in order and the corresponding sequences.
     * Order from 5' to 3'
     * Order by fmin partial = true, fmax partial = true, fmin
     * @param feature
     * @return
     */
    Assemblage generateAssemblageForFeature(Feature feature) {
        List<Sequence> sequenceList = new ArrayList<>()
        feature.featureLocations.sort() { a, b ->
            a.rank <=> b.rank ?: a.isFmaxPartial <=> b.isFmaxPartial ?: b.isFminPartial <=> a.isFminPartial ?: a.fmin <=> b.fmin
        }.each {
            if (!sequenceList.contains(it.sequence)) {
                sequenceList.add(it.sequence)
            }
        }
        return generateAssemblageForSequence(sequenceList)
    }


    Assemblage generateAssemblageForFeatures(Feature... features) {
        List<Sequence> sequenceList = new ArrayList<>()
        List<FeatureLocation> featureLocationList = new ArrayList<>()
        features.each { feature ->
            feature.featureLocations.each { featureLocation ->
                if (!featureLocationList.contains(featureLocation)) {
                    featureLocationList.add(featureLocation)
                }
            }
        }

        featureLocationList.sort() { a, b ->
            a.isFmaxPartial <=> b.isFmaxPartial ?: b.isFminPartial <=> a.isFminPartial ?: a.fmin <=> b.fmin
        }.each {
            if (!sequenceList.contains(it.sequence)) {
                sequenceList.add(it.sequence)
            }
        }

        // TODO: validate sequenceList against each feature and their location
        features.each {
            validateFeatureVsSequenceList(it, sequenceList)
        }

        return generateAssemblageForSequence(sequenceList)
    }

    /**
     * Here we want to guarantee that the sequence list exists in the same order as the
     * feature's feature locations.
     * @param feature
     * @param sequences
     * @return
     */
    def validateFeatureVsSequenceList(Feature feature, List<Sequence> sequences) {
        int lastRank = 0
        feature.featureLocations.sort() { it.rank }.each {
            int sequenceIndex = sequences.indexOf(it.sequence)
            if (sequenceIndex < lastRank || sequenceIndex < 0) {
                throw new AnnotationException("Sequence list does not match feature arrangement ${feature.name}")
            }
            lastRank = sequenceIndex
        }
        return true
    }

    Assemblage generateAssemblageForSequence(Sequence... sequences) {
        List<Sequence> sequenceList = new ArrayList<>()
        for (s in sequences) {
            sequenceList.add(s)
        }
        return generateAssemblageForSequence(sequenceList)
    }

    Assemblage generateAssemblageForSequence(List<Sequence> sequences) {
        Organism organism = sequences.first().organism
        JSONArray sequenceArray = new JSONArray()
        int end = 0;
        for (Sequence seq in sequences) {
            // note this creates the proper JSON string
            JSONObject sequenceObject = JSON.parse((seq as JSON).toString()) as JSONObject
            sequenceObject.reverse = false
            sequenceArray.add(sequenceObject)
            organism = organism ?: seq.organism
            end += seq.end
        }
        JSONObject testSequence = new JSONObject()
        testSequence.put(FeatureStringEnum.SEQUENCE_LIST.value, sequenceArray)
        testSequence.put(FeatureStringEnum.ORGANISM.value, organism.id)
        testSequence = standardizeSequenceList(testSequence)

        Assemblage assemblage = Assemblage.findByOrganismAndSequenceList(organism, testSequence.get(FeatureStringEnum.SEQUENCE_LIST.value).toString())
        assemblage = assemblage ?: new Assemblage(
                organism: organism
                , sequenceList: sequenceArray.toString()
                , start: 0
                , name: generateAssemblageName(sequenceArray)
                , end: end
        ).save(flush: true, failOnError: true)

        return assemblage
    }

    String generateAssemblageName(JSONArray sequenceArray) {
        String name = ""

        for (int i = 0; i < sequenceArray.size(); i++) {
            JSONObject sequenceObject = sequenceArray.getJSONObject(i)
            name += sequenceObject.name
            if (sequenceObject.containsKey(FeatureStringEnum.FEATURE.value)) {
                name += sequenceObject.getJSONObject(FeatureStringEnum.FEATURE.value).name + " " + name
            }
        }

        return name
    }

    List<Sequence> getSequencesFromAssemblage(Organism organism, String sequenceListString) {
        JSONArray sequenceArray = JSON.parse(sequenceListString) as JSONArray
        List<Sequence> sequenceList = []

        for (int i = 0; i < sequenceArray.size(); i++) {
            String sequenceName = sequenceArray.getJSONObject(i).name
            if (organism) {
                sequenceList << Sequence.findByOrganismAndName(organism, sequenceName)
            } else {
                sequenceList << Sequence.findByName(sequenceName)
            }
        }
        return sequenceList
    }

    List<Sequence> getSequencesFromAssemblage(Assemblage assemblage) {

        return getSequencesFromAssemblage(assemblage.organism, assemblage.sequenceList)
    }

    /**
     * TODO: does the automarshaller already do this?
     * @param assemblage
     * @return
     */
    // should match ProjectionDescription
    JSONObject convertAssemblageToJson(Assemblage assemblage) {
        JSONObject jsonObject = new JSONObject()
        jsonObject.id = assemblage.id
        jsonObject.projection = assemblage.projection ?: "NONE"


        jsonObject.padding = assemblage.padding ?: 0
//        jsonObject.referenceTrack = assemblage.referenceTrack

        jsonObject.payload = assemblage.payload ?: "{}"
        jsonObject.organism = assemblage.organism.commonName
        jsonObject.start = assemblage.start
        jsonObject.end = assemblage.end
        jsonObject.name = assemblage.name
//        jsonObject.name = URLEncoder.encode(assemblage.name,"UTF-8")
        // in theory these should be the same
        jsonObject.sequenceList = JSON.parse(assemblage.sequenceList) as JSONArray

        return jsonObject
    }

    JSONObject standardizeSequenceList(JSONObject inputObject) {
        JSONArray sequenceArray = JSON.parse(inputObject.getString(FeatureStringEnum.SEQUENCE_LIST.value)) as JSONArray
        Organism organism = null
        if (inputObject.containsKey(FeatureStringEnum.ORGANISM.value)) {
            organism = preferenceService.getOrganismForToken(inputObject.getString(FeatureStringEnum.ORGANISM.value))
        }
        if (!organism) {
            UserOrganismPreference userOrganismPreference = preferenceService.getCurrentOrganismPreference(inputObject.getString(FeatureStringEnum.CLIENT_TOKEN.value))
            organism = userOrganismPreference?.organism
        }
        List<Sequence> sequences1 = getSequencesFromAssemblage(organism, sequenceArray.toString())
        Map<String, Sequence> sequenceMap = sequences1.collectEntries() {
            [it.name, it]
        }

        for (int i = 0; i < sequenceArray.size(); i++) {
            JSONObject sequenceObject = sequenceArray.getJSONObject(i)
            Sequence sequence = sequenceMap.get(sequenceObject.name)
            sequenceObject.id = sequence.id
            sequenceObject.start = sequenceObject.start ?: sequence.start
            sequenceObject.end = sequenceObject.end ?: sequence.end
            sequenceObject.length = sequenceObject.length ?: sequence.length
        }
        inputObject.put(FeatureStringEnum.SEQUENCE_LIST.value, sequenceArray.toString())

        return inputObject
    }

    def getAssemblagesForUserAndOrganism(User user, Organism organism) {
        def assemblages = user.assemblages.findAll() {
            it.organism == organism
        }
        return assemblages
    }

    Assemblage convertJsonToAssemblage(JSONObject jsonObject) {
        standardizeSequenceList(jsonObject)
        JSONArray sequenceListArray = JSON.parse(jsonObject.getString(FeatureStringEnum.SEQUENCE_LIST.value)) as JSONArray
        Assemblage assemblage = Assemblage.findBySequenceList(sequenceListArray.toString())
        // now let's try it by ID
        if (assemblage == null && jsonObject.id) {
            assemblage = Assemblage.findById(jsonObject.id)
        }
        if (assemblage == null) {
            assemblage = Assemblage.findBySequenceList(sequenceListArray.toString())
        }
        if (assemblage == null) {
            log.info "creating assemblage from ${jsonObject as JSON} "
            assemblage = new Assemblage()
        }
//        assemblage.id = jsonObject.id
        assemblage.projection = jsonObject.projection
        assemblage.sequenceList = sequenceListArray.toString()
        assemblage.name = jsonObject.name ?: assemblage.name
        if (!assemblage.name) {
            assemblage.name = generateAssemblageName(sequenceListArray)
        }
        if (assemblage.name?.length() > 100) {
            assemblage.name = assemblage.name.substring(0, 99)
        }

        assemblage.start = jsonObject.containsKey(FeatureStringEnum.START.value) ? jsonObject.getLong(FeatureStringEnum.START.value) : sequenceListArray.getJSONObject(0).getInt(FeatureStringEnum.START.value)
        assemblage.end = jsonObject.containsKey(FeatureStringEnum.END.value) ? jsonObject.getLong(FeatureStringEnum.END.value) : sequenceListArray.getJSONObject(sequenceListArray.size() - 1).getInt(FeatureStringEnum.END.value)

        assemblage.organism = preferenceService.getOrganismFromInput(jsonObject)
        if (!assemblage.organism) {
            assemblage.organism = preferenceService.getCurrentOrganismForCurrentUser(jsonObject.getString(FeatureStringEnum.CLIENT_TOKEN.value))
        }
        assemblage.save()
        return assemblage
    }

    @NotTransactional
    static Boolean isProjectionReferer(String inputString) {
        return inputString.contains("(") && inputString.contains("):") && inputString.contains('..')
    }

    @NotTransactional
    static Boolean isProjectionString(String inputString) {
        return ((inputString.startsWith("{") && inputString.contains(FeatureStringEnum.SEQUENCE_LIST.value)) || (inputString.startsWith("[") && inputString.endsWith("]")))

    }

    /**
     * We want the minimimum location of a feature in the context of its assemblage
     * @param feature
     * @param assemblage
     * @return
     */
    int getMinForFeatureFullScaffold(Feature feature, Assemblage assemblage) {
        Integer calculatedMin = feature.fmin
        List<Sequence> sequencesList = getSequencesFromAssemblage(assemblage)

        Sequence firstSequence = feature.getFirstSequence()
        Integer sequenceOrder = sequencesList.indexOf(firstSequence)

        // add the entire length of each sequence in view
        for (int i = 0; i < sequenceOrder; i++) {
            calculatedMin += sequencesList.get(i).length
        }
        return calculatedMin
    }

    /**
     * We want the maximum location of a feature in the context of its assemblage
     * @param feature
     * @param assemblage
     * @return
     */
    int getMaxForFeatureFullScaffold(Feature feature, Assemblage assemblage) {
        Integer calculatedMax = feature.fmax
        List<Sequence> sequencesList = getSequencesFromAssemblage(assemblage)

        // we use the first sequence here, since fmax uses prior sequences
        Sequence firstSequence = feature.getFirstSequence()
        Integer sequenceOrder = sequencesList.indexOf(firstSequence)

        // add the entire length of each sequence in view
        for (int i = 0; i < sequenceOrder; i++) {
            calculatedMax += sequencesList.get(i).length
        }
        return calculatedMax
    }

    def removeAssemblageById(Long id, User user) {
        def assemblage = Assemblage.findById(id)
        if (assemblage) {
            def uops = UserOrganismPreference.findAllByAssemblage(assemblage)
            Boolean canDelete = uops.find() { it.currentOrganism } == null
            if (canDelete) {
                user.removeFromAssemblages(assemblage)
                uops.each {
                    it.delete()
                }
                assemblage.delete(flush: true)
                return true
            } else {
                log.error("Preference is still current, ignoring ${id}")
                return false
            }
        } else {
            log.error("No assemblage found to delete for ${id} and ${user.username}")
            return false
        }
    }

}