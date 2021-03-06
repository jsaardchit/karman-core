package com.bertramlabs.plugins.karman.openstack

import com.bertramlabs.plugins.karman.CloudFile
import groovy.json.JsonSlurper
import groovy.util.logging.Commons
import org.apache.http.Header
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpHead
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.utils.URIBuilder
import org.apache.http.entity.InputStreamEntity
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.message.BasicHeader
import org.apache.http.params.HttpConnectionParams
import org.apache.http.params.HttpParams
import org.apache.http.util.EntityUtils

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Openstack Cloud File implementation for the Openstack Cloud Files API v1
 * @author David Estes
 */
@Commons
class OpenstackCloudFile extends CloudFile {
	Map openstackMeta = [:]
	OpenstackDirectory parent
	private Boolean metaDataLoaded = false
	private Boolean chunked = false
	private Boolean existsFlag = null
	private InputStream writeStream

	/**
	 * Meta attributes setter/getter
	 */
	void setMetaAttribute(key, value) {
		openstackMeta[key] = value
	}

	OutputStream getOutputStream() {
		def outputStream = new PipedOutputStream()
		writeStream = new PipedInputStream(outputStream)
		return outputStream
	}

	void setInputStream(InputStream inputS) {
		writeStream = inputS
	}

	String getMetaAttribute(key) {
		if(!metaDataLoaded) {
			loadObjectMetaData()
		}
		return openstackMeta[key]
	}

	Map getMetaAttributes() {
		if(!metaDataLoaded) {
			loadObjectMetaData()
		}
		return openstackMeta
	}

	void removeMetaAttribute(key) {
		if(!metaDataLoaded) {
			loadObjectMetaData()
		}
		openstackMeta.remove(key)
	}

	/**
	 * Content length metadata
	 */
	Long getContentLength() {
		if(!metaDataLoaded) {
			loadObjectMetaData()
		}
		openstackMeta['Content-Length']?.toLong()
	}

	/**
	 * Used to set the new content length for the data being uploaded
	 * @param length
	 */
	void setContentLength(Long length) {
		setMetaAttribute('Content-Length', length)
	}

	/**
	 * Fetches the content type of the file being requested
	 */
	String getContentType() {
		if(!metaDataLoaded) {
			loadObjectMetaData()
		}
		openstackMeta['Content-Type']
	}

	/**
	 * Used to set the MimeType of the file being uploaded
	 * @param contentType the MimeType of the object
	 */
	void setContentType(String contentType) {
		setMetaAttribute("Content-Type", contentType)
	}


	/**
	 * Check if file exists
	 * @return true or false
	 */
	Boolean exists() {
		if(!metaDataLoaded) {
			loadObjectMetaData()
		}
		return existsFlag
	}



	/**
	 * Bytes setter/getter
	 */
	byte[] getBytes() {
		def result = inputStream?.bytes
		inputStream.close()
		return result
	}
	void setBytes(bytes) {
		writeStream = new ByteArrayInputStream(bytes)
		setContentLength(bytes.length)
	}

	/**
	 * Input stream getter
	 * @return inputStream
	 */
	InputStream getInputStream() {
		if(valid) {
			OpenstackStorageProvider openstackProvider = (OpenstackStorageProvider) provider
			URI listUri
			URIBuilder uriBuilder = new URIBuilder("${openstackProvider.getEndpointUrl()}/${parent.name}/${name}".toString())


			HttpGet request = new HttpGet(uriBuilder.build())
			request.addHeader("Accept", "application/json")
			request.addHeader(new BasicHeader('X-Auth-Token', openstackProvider.getToken()))
			HttpClient client = new DefaultHttpClient()
			HttpParams params = client.getParams()
			HttpConnectionParams.setConnectionTimeout(params, 30000)
			HttpConnectionParams.setSoTimeout(params, 20000)
			HttpResponse response = client.execute(request)

			openstackMeta = response.getAllHeaders()?.collectEntries() { Header header ->
				[(header.name): header.value]
			}

			metaDataLoaded = true
			HttpEntity entity = response.getEntity()
			return entity.content
		} else {
			return null
		}
	}

	/**
	 * Text setter/getter
	 * @param encoding
	 * @return text
	 */
	String getText(String encoding = null) {
		def result
		if (encoding) {
			result = inputStream?.getText(encoding)
		} else {
			result = inputStream?.text
		}
		inputStream?.close()
		return result
	}

	void setText(String text) {
		setBytes(text.bytes)
	}


	/**
	 * Save file
	 */
	def save(acl) {
		if (valid) {
			assert writeStream

			OpenstackStorageProvider openstackProvider = (OpenstackStorageProvider) provider

			// do chunk save
			if (openstackProvider.chunkSize > 0 && chunked) {
				return saveWithChunks(acl)
			}

			URI listUri
			URIBuilder uriBuilder = new URIBuilder("${openstackProvider.getEndpointUrl()}/${parent.name}/${name}".toString())


			HttpPut request = new HttpPut(uriBuilder.build())
			request.addHeader("Accept", "application/json")
			request.addHeader(new BasicHeader('X-Auth-Token', openstackProvider.getToken()))
			openstackMeta.each{entry ->
				if(entry.key != 'Content-Length') {
					request.addHeader(entry.key,entry.value.toString())
				}
			}
			request.setEntity(new InputStreamEntity(writeStream, this.getContentLength()))


			HttpClient client = new DefaultHttpClient()
			HttpParams params = client.getParams()
			HttpConnectionParams.setConnectionTimeout(params, 30000)
			HttpConnectionParams.setSoTimeout(params, 20000)

			HttpResponse response = client.execute(request)
			if(response.statusLine.statusCode != 201) {
				//Successfully Created File
				return false
			}

			metaDataLoaded = false
			openstackMeta = [:]

			existsFlag = true
			return true
		}
		return false
	}

	def saveWithChunks(acl) {
		OpenstackStorageProvider openstackProvider = (OpenstackStorageProvider) provider

		def segmentSize = openstackProvider.chunkSize
		def segmentBytesWritten = 0
		def segment = 1
		def buf = new byte[2048]
		def totalBytesRead = 0
		def token = openstackProvider.token

		// first write the data parts
		// get connection for first part
		try {
			def connection = getObjectStoreConnection(token, openstackProvider, segment, openstackMeta)
			connection.connect()
			def os = connection.outputStream

			def bytesRead = 0
			while ((bytesRead = writeStream.read(buf)) != -1) {
				os.write(buf, 0, bytesRead)
				segmentBytesWritten += bytesRead
				totalBytesRead += bytesRead
				os.flush()

				if (segmentSize - (segmentBytesWritten + buf.size()) < 0 || bytesRead < buf.size()) {
					def oldConnection = connection
					try {
						os.flush()
						if (oldConnection.responseCode == 201) {
							segment++
							segmentBytesWritten = 0
							if (bytesRead == buf.size()) {
								connection = getObjectStoreConnection(token, openstackProvider, segment, openstackMeta)
								os = connection.outputStream
								log.info("creating ${name} part${segment}")
							}
						}
						else {
							log.error("Failed to write data: ${oldConnection.responseCode} - ${oldConnection.responseMessage}")
							throw new RuntimeException("Failed to write data: ${oldConnection.responseCode} - ${oldConnection.responseMessage}")
						}
					}
					finally {
						oldConnection.disconnect()
					}
				}
			}
			log.debug("Total bytes read: ${totalBytesRead}")
		}
		catch (Throwable t) {
			return false
		}
		finally {
			writeStream.close()
		}

		// then write the metadata part
		def headers = ['X-Object-Manifest':"${parent.name}/${name}/"]
		def connection
		try {
			// log.debug("Writing manifest for ${name}")
			connection = getObjectStoreConnection(token, openstackProvider, null, headers)
			connection.connect()
			def osw = new OutputStreamWriter(connection.outputStream)
			osw.write('')
			osw.flush()
			if (connection.responseCode != 201) {
				return false
				//log.error("Failed to write manifest data: ${connection.responseCode} - ${connection.responseMessage}")
			}
		}
		catch (Throwable t) {
			// log.error("Failed to write manifest data", t)
			return false
		}
		finally {
			connection.disconnect()
		}
		return true
	}

	private getObjectStoreConnection(String token, OpenstackStorageProvider provider, Integer segment, Map headers = [:]) {
		try {
			def part = segment ? "/part${segment}" : ''
			def url = new URL("${provider.getEndpointUrl()}/${parent.name}/${name}${part}".toString())
			def connection = url.openConnection()
			log.info("File URL: ${url}")
			connection.setRequestMethod('PUT')
			connection.setRequestProperty('X-Auth-Token', token)
			connection.setDoOutput(true)

			// add any additional headers
			headers.each {
				connection.setRequestProperty(it.key, it.value)
			}
			return connection
		}
		catch (Throwable t) {
			log.error('Error building url connection to openstack object store', t)
			throw t
		}
	}

	/**
	 * Delete file
	 */
	def delete() {
		if (valid) {
			s3Client.deleteObject(parent.name, name)
			existsFlag = false
		}
	}

	/**
	 * Get URL or pre-signed URL if expirationDate is set
	 * @param expirationDate
	 * @return url
	 */
	URL getURL(Date expirationDate = null) {
		if (valid) {
			OpenstackStorageProvider openstackProvider = (OpenstackStorageProvider) provider
			if (expirationDate) {
				String objectPath = openstackProvider.endpointUrl.split("/v1/")[1] + "/${parent.name}/${name}"
				objectPath = "/v1/" + objectPath
				String hmacBody = "GET\n${(expirationDate.time/1000).toLong()}\n${objectPath}".toString()
				SecretKeySpec key = new SecretKeySpec((openstackProvider.tempUrlKey).getBytes("UTF-8"), "HmacSHA1");
				Mac mac = Mac.getInstance("HmacSHA1");
				mac.init(key);
				String sig = mac.doFinal(hmacBody.getBytes('UTF-8')).encodeHex().toString()
				new URL("${openstackProvider.endpointUrl}/${parent.name}/${name}?temp_url_sig=${sig}&temp_url_expires=${(expirationDate.time/1000).toLong()}")
			} else {
				new URL("${openstackProvider.endpointUrl}/${parent.name}/${name}")
			}
		}
	}

	private void loadObjectMetaData() {
		if(valid) {
			OpenstackStorageProvider openstackProvider = (OpenstackStorageProvider) provider
			URI listUri
			URIBuilder uriBuilder = new URIBuilder("${openstackProvider.getEndpointUrl()}/${parent.name}/${name}".toString())


			HttpHead request = new HttpHead(uriBuilder.build())
			request.addHeader("Accept", "application/json")
			request.addHeader(new BasicHeader('X-Auth-Token', openstackProvider.getToken()))
			HttpClient client = new DefaultHttpClient()
			HttpParams params = client.getParams()
			HttpConnectionParams.setConnectionTimeout(params, 30000)
			HttpConnectionParams.setSoTimeout(params, 20000)
			HttpResponse response = client.execute(request)
			if(response.statusLine.statusCode == 404) {
				existsFlag = false
				return
			}
			existsFlag = true
			openstackMeta = response.getAllHeaders()?.collectEntries() { Header header ->
				[(header.name): header.value]
			}
			EntityUtils.consume(response.entity)
			metaDataLoaded = true
		}
	}

	private boolean isValid() {
		assert parent
		assert parent.name
		assert name
		true
	}
}
