stage('Monitor'){
    when {
        expression { params.StandardCfnOperation == 'Montior_SQ' }
    }
    steps {
        script {
            buildName "#${BUILD_NUMBER}-${params.accountNo}"
            buildDescription "BLZ Account - ${params.accountNo}, Operation - ${params.StandardCfnOperation}, QuotaForServices - ${params.QuotaForServices}"
            
            withCredentials([usernamePassword(credentialsId: "${params.awsCredentialsId}", passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                generateLoginConfig(env.USERNAME, env.PASSWORD, params.RoleArn)
            }
            awsexe("configure set region ${params.BLZAWSRegion}")

            def vpcJson = awsexe("ec2 describe-vpcs --region ${params.BLZAWSRegion}")
            def vpcs = readJSON text: vpcJson
            FreeIptable = "<table border=1 style='display: inline-block'><tr><th>VPC ID</th><th>Subnet ID</th><th>Utilized IPs</th><th>Max IPs in Subnet</th><th>Utilization %</th></tr>"
            used80Iptable = "<table border=1 style='display: inline-block'><tr><th>VPC ID</th><th>Subnet ID</th><th>Utilized IPs</th><th>Max IPs in Subnet</th><th>Utilization %</th></tr>"

            vpcs.Vpcs.each { vpc ->
                def vpcId = vpc.VpcId
                def subnetsJson = awsexe("ec2 describe-subnets --filters \"Name=vpc-id,Values=${vpcId}\" --region ${params.BLZAWSRegion}")
                def subnets = readJSON text: subnetsJson

                subnets.Subnets.each { subnet ->
                    def subnetId = subnet.SubnetId
                    subnetMask = subnet.CidrBlock.split('/')[1]
                    subnetBits = 32 - (subnetMask as Integer)
                    totalIps = 1 << subnetBits
                    def freeIPs = subnet.AvailableIpAddressCount as Integer
                    usedIps = totalIps - freeIPs
                    utilization = ((totalIps - freeIPs) / totalIps) * 100
                    utilizationValue = utilization.toString().split("\\.")[0]
                    color = totalIps * 0.2 > freeIPs ? "red" : "white"
                    used80Iptable += "<tr><th>${vpcId}</th><th>${subnet.SubnetId}</th><td style=\"text-align:center\" bgcolor=\"${color}\">${usedIps}</td><th>${totalIps}</th><td style=\"text-align:center\" bgcolor=\"${color}\">${utilizationValue}%</td></tr>"
                    FreeIptable += "<tr><th>${vpcId}</th><th>${subnet.SubnetId}</th><td style=\"text-align:center\" bgcolor=\"${color}\">${usedIps}</td><th>${totalIps}</th><td style=\"text-align:center\" bgcolor=\"${color}\">${utilizationValue}%</td></tr>"
                }
            }
            FreeIptable += "</table>"
            used80Iptable += "</table>"

            allQuotaDetails = readJSON(text: readFile("service-quota-data.json").trim())
            alert = []
            QuotaValue = [:]
            allQuotaDetails.each { service ->
                QuotaValue[service.ServiceCode] = [:]
                service.Quotas.each { quota ->
                    if (quota.MonitoringRequired) {
                        QuotaValue[service.ServiceCode][quota.QuotaCode] = [:]
                        MonitoringRequiredFlag = true
                        QuotaLimit = quota.DefaultLimit ?: 0.8

                        if (params.ApplyCustomList) {
                            MonitoringRequiredFlag = false
                            serviceDetails = servicestobeTest.find { it.ServiceCode == service.ServiceCode }
                            if (serviceDetails) {
                                QuotaDetails = serviceDetails.Quotas.find { it.QuotaCode == quota.QuotaCode }
                                if (QuotaDetails) {
                                    QuotaValue[service.ServiceCode][quota.QuotaCode] = [:]
                                    MonitoringRequiredFlag = true
                                    QuotaLimit = QuotaDetails.CustomLimit ?: QuotaLimit
                                }
                            }
                        } else {
                            QuotaValue[service.ServiceCode][quota.QuotaCode] = [:]
                        }

                        if (MonitoringRequiredFlag) {
                            regionValue = quota.Region ?: params.BLZAWSRegion
                            commandWithRegion = quota.Command.replaceAll("varRegion", params.BLZAWSRegion)

                            try {
                                retry(5) {
                                    TrustedAdvisorData = awsexe(commandWithRegion)
                                    TrustedAdvisorJSon = readJSON text: TrustedAdvisorData

                                    if (TrustedAdvisorJSon.isEmpty()) {
                                        actualValue = "0"
                                        actualcount = actualValue.toInteger()
                                        quotaDetails = readJSON text: awsexe("service-quotas get-service-quota --region ${regionValue} --service-code ${service.ServiceCode} --quota-code ${quota.QuotaCode}")
                                        quotaValue = quotaDetails.Quota.Value
                                    } else if (quota.Global) {
                                        actualValue = TrustedAdvisorJSon[0].metadata[4]
                                        actualcount = actualValue.toInteger()
                                        quotaValue = TrustedAdvisorJSon[0].metadata[3].toInteger()
                                    } else {
                                        TrustedAdvisorRegion = TrustedAdvisorJSon.find { o -> o.region == "${regionValue}" }
                                        actualValue = TrustedAdvisorRegion.metadata[4]
                                        actualcount = actualValue.toInteger()
                                        quotaValue = TrustedAdvisorRegion.metadata[3].toInteger()
                                    }
                                }
                            } catch (e) {
                                echo "Unable to fetch details so marking as quota Zero"
                                actualcount = 0
                                quotaDetails = readJSON text: awsexe("service-quotas get-service-quota --region ${regionValue} --service-code ${service.ServiceCode} --quota-code ${quota.QuotaCode}")
                                quotaValue = quotaDetails.Quota.Value
                            }

                            utilization = (actualcount / quotaValue) * 100
                            utilizationValue = utilization.toString().split("\\.")[0].toInteger()
                            QuotaValue[service.ServiceCode][quota.QuotaCode] = [
                                "ServiceCode": service.ServiceCode,
                                "QuotaName": quota.QuotaName,
                                "actualcount": actualcount,
                                "quotaValue": quotaValue,
                                "utilizationValue": utilizationValue,
                                "errorFlag": errorFlag,
                                "QuotaLimit": QuotaLimit
                            ]
                        }
                    }
                }
            }

            result = ""
            QuotaValue.each { serviceCode, quotas ->
                quotas.each { quotaCode, quotaDetails ->
                    if (quotaDetails) {
                        ServiceCode = quotaDetails["ServiceCode"]
                        QuotaName = quotaDetails["QuotaName"]
                        actualcount = quotaDetails["actualcount"]
                        quotaValue = quotaDetails["quotaValue"]
                        utilizationValue = quotaDetails["utilizationValue"]
                        errorFlag = quotaDetails["errorFlag"]
                        QuotaLimit = quotaDetails["QuotaLimit"]

                        column = "<tr><td style=\"text-align:center\">${ServiceCode}</td><td style=\"text-align:center\">${QuotaName}</td><td style=\"text-align:center\">${actualcount}</td><td style=\"text-align:center\">${quotaValue}</td><td style=\"text-align:center\">${utilizationValue}%</td></tr>"
                        if (errorFlag) {
                            alert = true
                            column = "<tr><td style=\"text-align:center\">${ServiceCode}</td><td style=\"text-align:center\">${QuotaName}</td><td style=\"text-align:center\" bgcolor=\"yellow\">Not able to fetch data</td><td style=\"text-align:center\">${quotaValue}</td><td style=\"text-align:center\">${utilizationValue}%</td></tr>"
                        } else if (quotaValue * QuotaLimit < actualcount.toInteger()) {
                            alert = true
                            column = "<tr><td style=\"text-align:center\">${ServiceCode}</td><td style=\"text-align:center\">${QuotaName}</td><td style=\"text-align:center\" bgcolor=\"red\">${actualcount}</td><td style=\"text-align:center\">${quotaValue}</td><td style=\"text-align:center\" bgcolor=\"red\">${utilizationValue}%</td></tr>"
                        }
                        result += column
                    }
                }
            }

            // Send email or other actions based on result
            if (alert) {
                // Code to send alert email with result
            }
        }
    }
}
