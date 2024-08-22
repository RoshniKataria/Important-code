stage('Monitor') {
    when {
        expression { params.StandardCfnOperation == 'Monitor_SQ' }
    }
    steps {
        script {
            buildName "#${BUILD_NUMBER}-${params.accountNo}"
            buildDescription "BLZ Account - ${params.accountNo}, Operation - ${params.StandardCfnOperation}, QuotaForServices - ${params.QuotaForServices}"

            def vpcTasks = [:]
            def quotaTasks = [:]
            def allQuotaDetails = readJSON(text: readFile("service-quota-data.json").trim())
            def resultQuota = ""

            // Function to process each quota
            def processQuota = { serviceCode, quota ->
                def quotaName = quota.QuotaName
                def commandWithRegion = quota.Command.replaceAll("varRegion", params.BLZAWSRegion)
                def regionValue = quota.Region ?: params.BLZAWSRegion
                def quotaCode = quota.QuotaCode
                def errorFlag = false
                def column = ""

                retry(5) {
                    def actualcount = 0
                    def quotaValue = 0

                    if (quota.TrustedAdvisor) {
                        def TrustedAdvisorData = awsexe(commandWithRegion)
                        def TrustedAdvisorJSon = readJSON text: TrustedAdvisorData

                        if (TrustedAdvisorJSon.isEmpty()) {
                            def quotaDetails = readJSON text: awsexe("service-quotas get-service-quota --region ${regionValue} --service-code ${serviceCode} --quota-code ${quotaCode}")
                            quotaValue = quotaDetails.Quota.Value
                            actualcount = 0
                        } else {
                            def metadata = quota.Global ? TrustedAdvisorJSon[0].metadata : TrustedAdvisorJSon.find { it.region == regionValue }?.metadata
                            actualcount = metadata[4].toInteger()
                            quotaValue = metadata[3].toInteger()
                        }
                    } else {
                        try {
                            def quotaDetails = readJSON text: awsexe("service-quotas get-service-quota --region ${regionValue} --service-code ${serviceCode} --quota-code ${quotaCode}")
                            quotaValue = quotaDetails.Quota.Value
                        } catch (e) {
                            echo "Unable to fetch quota value, using default value"
                            quotaValue = quota.DefaultValue
                        }

                        if (quota.PreCommand) {
                            def preCommandWithRegion = quota.PreCommand.replaceAll("varRegion", params.BLZAWSRegion)
                            def resources = readJSON text: awsexe(preCommandWithRegion).trim()
                            actualcount = resources.collect { resource ->
                                def commandWithResource = commandWithRegion.replaceAll("varResource", resource)
                                def actualValue = awsexe(commandWithResource).trim().toInteger()
                                actualValue
                            }.max() ?: 0
                        } else {
                            actualcount = awsexe(commandWithRegion).trim().toFloat().toInteger()
                        }
                    }

                    def utilization = (actualcount / quotaValue) * 100
                    def utilizationValue = utilization.toString().split("\\.")[0].toInteger()
                    column = "<tr><td style=\"text-align:center\">${serviceCode}</td><td style=\"text-align:center\">${quotaName}</td><td style=\"text-align:center\">${actualcount}</td><td style=\"text-align:center\">${quotaValue}</td><td style=\"text-align:center\">${utilizationValue}%</td></tr>"

                    if (errorFlag) {
                        column = "<tr><td style=\"text-align:center\">${serviceCode}</td><td style=\"text-align:center\">${quotaName}</td><td style=\"text-align:center\" bgcolor=\"yellow\">Not able to fetch data</td><td style=\"text-align:center\">${quotaValue}</td><td style=\"text-align:center\">${utilizationValue}%</td></tr>"
                    } else if (quotaValue * 0.8 < actualcount) {
                        column = "<tr><td style=\"text-align:center\">${serviceCode}</td><td style=\"text-align:center\">${quotaName}</td><td style=\"text-align:center\" bgcolor=\"red\">${actualcount}</td><td style=\"text-align:center\">${quotaValue}</td><td style=\"text-align:center\" bgcolor=\"red\">${utilizationValue}%</td></tr>"
                        if (params.SetuEnable) {
                            def message_payload = "{\"action\": \"create\",\"region\": \"${params.BLZAWSRegion}\",\"env\": \"${params.Environment}\",\"accountNo\": \"${params.accountNo}\",\"service_name\": \"${serviceCode}-${quotaName}\",\"quota_value\": \"${quotaValue}\",\"actual_value\": \"${actualcount}\"}"
                            awsexe("lambda invoke --function-name ${params.LambdaName} --cli-read-timeout 20 --cli-binary-format raw-in-base64-out --payload \'${message_payload}\' out.json")
                        }
                    }
                }

                return column
            }

            // Process each service in parallel
            allQuotaDetails.each { service ->
                def serviceCode = service.ServiceCode
                quotaTasks[serviceCode] = {
                    def serviceColumn = ""
                    def quotaSubTasks = [:]

                    service.Quotas.each { quota ->
                        quotaSubTasks[quota.QuotaName] = {
                            def column = processQuota(serviceCode, quota)
                            serviceColumn += column
                        }
                    }

                    parallel quotaSubTasks
                    return serviceColumn
                }
            }

            parallel quotaTasks.each { serviceCode, result ->
                resultQuota += result
            }

            def finalResult = "<table border=1 style='display: inline-block'><tr><th>Service Code</th><th>Quota Name</th><th>Used</th><th>Limit</th><th>Utilization</th></tr>" + resultQuota + "</table>"

            emailext(
                subject: "[Alert] ${params.StandardCfnOperation} for Account ${params.accountNo}",
                body: finalResult,
                to: "your-email@example.com"
            )
        }
    }
}
