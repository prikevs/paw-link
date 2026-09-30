import Foundation
import Network

let actionIDs = ["feed", "jump", "groom", "wash", "roll", "walk", "sleep"]
struct StateUpdate: Decodable { let action: String }

// One bounded HTTP/1.1 request per connection; callbacks and pet state stay on main queue.
final class PetAPI {
    let pet: PetView
    var listener: NWListener?
    var status = "API 启动中"
    var source = "manual"
    var updatedAt = ISO8601DateFormatter().string(from: Date())
    init(pet: PetView) { self.pet = pet }
    func start() throws {
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: 8766)
        let listener = try NWListener(using: params)
        self.listener = listener
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready: self.status = "API：127.0.0.1:8766"
            case .failed(let error): self.status = "API 无法启动：\(error.localizedDescription)"; listener.cancel()
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            connection.start(queue: .main)
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { connection.cancel() }
            self.receive(connection, buffer: Data())
        }
        listener.start(queue: .main)
    }
    func snapshot() -> [String: Any] {
        ["action":actionIDs[pet.selected], "label":motions[pet.selected].name,
         "paused":pet.paused, "source":source, "updated_at":updatedAt]
    }
    func changed(source: String) { self.source = source; updatedAt = ISO8601DateFormatter().string(from: Date()) }
    func respond(_ c: NWConnection, _ code: Int, _ object: [String:Any]) {
        let body = try! JSONSerialization.data(withJSONObject:object,options:[.sortedKeys])
        let reason = [200:"OK",400:"Bad Request",403:"Forbidden",404:"Not Found",405:"Method Not Allowed",411:"Length Required",413:"Payload Too Large",415:"Unsupported Media Type"][code] ?? "Error"
        var data = Data("HTTP/1.1 \(code) \(reason)\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: \(body.count)\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".utf8)
        data.append(body)
        c.send(content:data,completion:.contentProcessed { _ in c.cancel() })
    }
    func receive(_ c: NWConnection, buffer: Data) {
        c.receive(minimumIncompleteLength:1,maximumLength:8192) { [weak self] data, _, complete, error in
            guard let self else { c.cancel(); return }
            var buffer = buffer; if let data { buffer.append(data) }
            guard buffer.count <= 16384 else { self.respond(c,413,["error":"request_too_large"]); return }
            if let split = buffer.range(of: Data("\r\n\r\n".utf8)) {
                guard let header = String(data:buffer[..<split.lowerBound],encoding:.utf8) else { self.respond(c,400,["error":"invalid_headers"]); return }
                let lines = header.components(separatedBy:"\r\n")
                let request = lines[0].split(separator:" ")
                guard request.count == 3, request[2] == "HTTP/1.1" else { self.respond(c,400,["error":"invalid_request"]); return }
                var headers = [String:String]()
                for line in lines.dropFirst() {
                    guard let colon = line.firstIndex(of:":") else { self.respond(c,400,["error":"invalid_header"]); return }
                    let key = line[..<colon].lowercased()
                    guard headers[key] == nil else { self.respond(c,400,["error":"duplicate_header"]); return }
                    headers[key] = line[line.index(after:colon)...].trimmingCharacters(in:.whitespaces)
                }
                guard ["127.0.0.1:8766", "localhost:8766"].contains(headers["host"] ?? ""), headers["origin"] == nil else {
                    self.respond(c,403,["error":"local_clients_only"]); return
                }
                guard headers["transfer-encoding"] == nil else { self.respond(c,400,["error":"chunked_not_supported"]); return }
                let method = String(request[0]), path = String(request[1])
                if method == "POST" && headers["content-length"] == nil { self.respond(c,411,["error":"content_length_required"]); return }
                guard let length = Int(headers["content-length"] ?? "0"), length >= 0, length <= 4096 else { self.respond(c,413,["error":"invalid_body_length"]); return }
                let body = Data(buffer[split.upperBound...])
                if body.count >= length {
                    guard body.count == length else { self.respond(c,400,["error":"extra_body_bytes"]); return }
                    self.route(c,method:method,path:path,headers:headers,body:body); return
                }
            }
            if complete || error != nil { self.respond(c,400,["error":"incomplete_request"]); return }
            self.receive(c,buffer:buffer)
        }
    }
    func route(_ c: NWConnection, method: String, path: String, headers: [String:String], body: Data) {
        if method == "GET" && path == "/health" { respond(c,200,["status":"ok","api_version":1]); return }
        if method == "GET" && path == "/v1/actions" {
            respond(c,200,["actions":actionIDs.enumerated().map { ["action":$0.element,"label":motions[$0.offset].name] }]); return
        }
        if method == "GET" && path == "/v1/state" { respond(c,200,snapshot()); return }
        if method == "POST" && path == "/v1/state" {
            guard headers["content-type"]?.lowercased().split(separator:";").first?.trimmingCharacters(in:.whitespaces) == "application/json" else {
                respond(c,415,["error":"application_json_required"]); return
            }
            guard let update = try? JSONDecoder().decode(StateUpdate.self,from:body), let index = actionIDs.firstIndex(of:update.action) else {
                respond(c,400,["error":"invalid_action","allowed_actions":actionIDs]); return
            }
            // Repeated collar reports must not restart the animation every sample.
            if pet.selected != index { pet.elapsed = 0 }
            pet.selected = index; pet.paused = false; pet.updateLabel(); pet.needsDisplay = true
            changed(source:"collar")
            respond(c,200,snapshot()); return
        }
        respond(c,["/health","/v1/actions","/v1/state"].contains(path) ? 405 : 404,["error":"unsupported_route_or_method"])
    }
}
