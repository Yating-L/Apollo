package org.bbop.apollo

import grails.plugins.rest.client.RestBuilder
import grails.plugins.rest.client.RestResponse
import org.bbop.apollo.report.PerformanceMetric
import org.codehaus.groovy.grails.web.json.JSONObject
import org.grails.plugins.metrics.groovy.Timed

class HomeController {

    @Timed(name = "SystemInfo")
    def systemInfo() {

        Map<String, String> runtimeMapInstance = new HashMap<>()
        Map<String, String> servletMapInstance = new HashMap<>()
        Map<String, String> javaMapInstance = new HashMap<>()

        javaMapInstance.putAll(System.getenv())

        servletContext.getAttributeNames().each {
            servletMapInstance.put(it, servletContext.getAttribute(it))
        }

        runtimeMapInstance.put("Available processors", "" + Runtime.getRuntime().availableProcessors())
        runtimeMapInstance.put("Free memory", Runtime.getRuntime().freeMemory() / 1E6 + " MB")
        runtimeMapInstance.put("Max memory", "" + Runtime.getRuntime().maxMemory() / 1E6 + " MB")
        runtimeMapInstance.put("Total memory", "" + Runtime.getRuntime().totalMemory() / 1E6 + " MB")

//        servletContext
        render view: "systemInfo", model: [javaMapInstance: javaMapInstance, servletMapInstance: servletMapInstance, runtimeMapInstance: runtimeMapInstance]
    }

    private String getMethodName(String timerName) {
        return timerName.substring(timerName.lastIndexOf(".") + 1).replaceAll("Timer", "")
    }

    private String getClassName(String timerName) {
        return timerName.substring("org.bbop.apollo.".length(), timerName.lastIndexOf("."))
    }

    def metrics() {
        def link = createLink(absolute: true, action: "metrics", controller: "metrics")
        RestBuilder rest = new RestBuilder()
        RestResponse response = rest.get(link)
        JSONObject timerObjects = (response.json as JSONObject).getJSONObject("timers")

        List<PerformanceMetric> performanceMetricList = new ArrayList<>()
        Long countTotal = 0
        Double meanTotal = 0

        for (String timerName : timerObjects.keySet()) {
//            JSONObject jsonObject = timerObjects.getJSONObject(i).getJSONObject("timers")
            PerformanceMetric metric = new PerformanceMetric()
//            println "timerName: [${timerName}]"
            metric.className = getClassName(timerName)
            metric.methodName = getMethodName(timerName)
            JSONObject timerData = timerObjects.getJSONObject(timerName)
            metric.count = timerData.getInt("count")
            metric.min = timerData.getDouble("min")
            metric.max = timerData.getDouble("max")
            metric.mean = timerData.getDouble("mean")
            metric.stddev = timerData.getDouble("stddev")

            countTotal += metric.count
            meanTotal += metric.mean

            performanceMetricList.add(metric)
        }

//        http://localhost:8080/apollo/metrics/metrics?pretty=true
        performanceMetricList.sort(true) { a, b ->
            b.mean <=> a.mean
            b.count <=> a.count
        }


        render view: "metrics", model: [performanceMetricList: performanceMetricList, countTotal: countTotal, meanTotal: countTotal ? meanTotal / countTotal : 0]
    }

    def downloadReport() {

        String returnString = "class,method,count,mean,max,min,stddev\n"
////        println "response text ${response.text}"
        def link = createLink(absolute: true, action: "metrics", controller: "metrics")
        RestBuilder rest = new RestBuilder()
        RestResponse restResponse = rest.get(link)
        JSONObject timerObjects = (restResponse.json as JSONObject).getJSONObject("timers")
        for (String timerName : timerObjects.keySet()) {
            returnString += getClassName(timerName) +","+getMethodName(timerName)+","
            JSONObject timerData = timerObjects.getJSONObject(timerName)
            returnString += timerData.getInt("count")
            returnString += timerData.getDouble("min")
            returnString += timerData.getDouble("max")
            returnString += timerData.getDouble("mean")
            returnString += timerData.getDouble("stddev")
            returnString += "\n"
        }



        String fileName = "apollo-performance-metrics-${new Date().format('dd-MMM-yyyy')}"
        response.setHeader "Content-disposition", "attachment; filename=${fileName}.csv"
        response.setHeader("x-filename", "${fileName}.csv")
        response.contentType = 'text/csv'
        response.outputStream << returnString
        response.outputStream.flush()
    }
}
