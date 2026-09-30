import Cocoa

struct Motion {
    let name: String
    let file: String
    let cols: Int
    let order: [Int]
    let durations: [Double]
}
let motions = [
    Motion(name:"吃饱喝足",file:"eat-sheet-v1",cols:3,order:Array(0..<6),durations:[320,350,200,240,460,320]),
    Motion(name:"跳起来玩",file:"jump-sheet-v1",cols:3,order:Array(0..<6),durations:[600,160,180,150,200,500]),
    Motion(name:"舔毛",file:"groom-sheet-v1",cols:3,order:Array(0..<6),durations:[350,200,180,220,180,400]),
    Motion(name:"洗脸",file:"wash-large-v5",cols:4,order:Array(0..<8),durations:[380,180,220,200,180,180,200,260]),
    Motion(name:"左右打滚",file:"roll-photo-v3",cols:4,order:[0,1,2,3,4,5,6,5,0],durations:[350,180,250,180,160,180,250,180,150]),
    Motion(name:"走路",file:"walk-sheet-v1",cols:4,order:Array(0..<8),durations:Array(repeating:145,count:8)),
    Motion(name:"睡觉",file:"sleep-v1",cols:1,order:[0],durations:[3600])
]
struct Sprite { let image: NSImage; let box: CGRect; let cell: CGSize }

// Remove only bright background connected to the cell edges, preserving pale belly fur.
func sprites(_ motion: Motion) throws -> [Sprite] {
    guard let url = Bundle.main.url(forResource:motion.file,withExtension:"png"),
          let source = NSImage(contentsOf:url),
          let cg = source.cgImage(forProposedRect:nil,context:nil,hints:nil) else {
        throw NSError(domain:"PawLink",code:1,userInfo:[NSLocalizedDescriptionKey:"找不到动作素材：\(motion.name)"])
    }
    let rows = motion.cols == 1 ? 1 : 2
    let w = cg.width / motion.cols, h = cg.height / rows
    return try (0..<(motion.cols*rows)).map { index in
        guard let crop = cg.cropping(to:CGRect(x:index%motion.cols*w,y:index/motion.cols*h,width:w,height:h)) else { throw NSError(domain:"crop",code:1) }
        var pixels = [UInt8](repeating:0,count:w*h*4)
        let image: CGImage? = pixels.withUnsafeMutableBytes { buffer in
            guard let ctx = CGContext(data:buffer.baseAddress,width:w,height:h,bitsPerComponent:8,bytesPerRow:w*4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
            ctx.draw(crop,in:CGRect(x:0,y:0,width:w,height:h))
            let p = buffer.bindMemory(to:UInt8.self)
            var seen = [Bool](repeating:false,count:w*h), queue = [Int]()
            func push(_ i:Int) {
                if seen[i] { return }; seen[i] = true
                let j=i*4
                if min(p[j],p[j+1],p[j+2]) >= 225 { queue.append(i) }
            }
            for x in 0..<w { push(x); push((h-1)*w+x) }
            for y in 0..<h { push(y*w); push(y*w+w-1) }
            var head=0
            while head < queue.count {
                let i=queue[head]; head += 1; p[i*4+3]=0; p[i*4]=0; p[i*4+1]=0; p[i*4+2]=0
                let x=i%w,y=i/w
                if x>0 { push(i-1) }; if x<w-1 { push(i+1) }
                if y>0 { push(i-w) }; if y<h-1 { push(i+w) }
            }
            return ctx.makeImage()
        }
        guard let image else { throw NSError(domain:"image",code:2) }
        var left=w,top=h,right=0,bottom=0
        for y in 0..<h { for x in 0..<w {
            if pixels[(y*w+x)*4+3] > 0 { left=min(left,x);right=max(right,x);top=min(top,y);bottom=max(bottom,y) }
        }}
        let box=CGRect(x:left,y:top,width:max(1,right-left+1),height:max(1,bottom-top+1))
        return Sprite(image:NSImage(cgImage:image,size:NSSize(width:w,height:h)),box:box,cell:CGSize(width:w,height:h))
    }
}

class PetWindow: NSPanel { override var canBecomeKey: Bool { false } }
class PetView: NSView {
    var api: PetAPI?
    var tracks = [[Sprite]]()
    var selected = 6
    var elapsed = 0.0
    var paused = false
    var last = ProcessInfo.processInfo.systemUptime
    var timer: Timer?
    var dragOrigin = NSPoint.zero
    var mouseOrigin = NSPoint.zero
    override var isFlipped: Bool { true }
    override var isOpaque: Bool { false }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    func prepare() throws {
        tracks = try motions.map { try sprites($0) }
        setAccessibilityElement(true)
        setAccessibilityRole(.image)
        updateLabel()
        timer = Timer(timeInterval:1.0/30,repeats:true) { [weak self] _ in self?.tick() }
        RunLoop.main.add(timer!,forMode:.common)
    }
    func updateLabel() { setAccessibilityLabel("PawLink 桌面宠物：\(motions[selected].name)，右键选择动作，拖动改变位置") }
    func tick() {
        let now=ProcessInfo.processInfo.systemUptime
        if !paused { elapsed += min(now-last,0.1)*1000; needsDisplay=true }
        last=now
    }
    func frameIndex() -> Int {
        let m=motions[selected]; var t=elapsed.truncatingRemainder(dividingBy:m.durations.reduce(0,+))
        for i in m.durations.indices { if t<m.durations[i] { return m.order[i] }; t -= m.durations[i] }
        return m.order[0]
    }
    override func draw(_ dirtyRect: NSRect) {
        NSColor.clear.setFill(); bounds.fill(using:.copy)
        guard !tracks.isEmpty else { return }
        let frames=tracks[selected], f=frames[frameIndex()]
        let maxW=frames.map{$0.box.width}.max()!, maxH=frames.map{$0.box.height}.max()!
        let s=min((bounds.width-24)/maxW,(bounds.height-28)/maxH)
        var x=(bounds.width-f.box.width*s)/2-f.box.minX*s
        var y=bounds.height-12-f.box.maxY*s
        if selected==1 || selected==4 {
            let union=frames.reduce(CGRect.null){$0.union($1.box)}
            x=(bounds.width-union.width*s)/2-union.minX*s
            y=bounds.height-12-union.maxY*s
        }
        var height=f.cell.height*s
        if selected==6 {
            let breath=1+0.016*(1-cos(elapsed/3600*2*Double.pi))/2
            height *= breath
            y=bounds.height-12-f.box.maxY*s*breath
        }
        f.image.draw(in:CGRect(x:x,y:y,width:f.cell.width*s,height:height),from:.zero,operation:.sourceOver,fraction:1,respectFlipped:true,hints:[.interpolation:NSImageInterpolation.high])
    }
    override func mouseDown(with event:NSEvent) {
        if event.modifierFlags.contains(.control) { rightMouseDown(with:event); return }
        dragOrigin=window!.frame.origin; mouseOrigin=NSEvent.mouseLocation
    }
    override func mouseDragged(with event:NSEvent) {
        let p=NSEvent.mouseLocation
        window?.setFrameOrigin(NSPoint(x:dragOrigin.x+p.x-mouseOrigin.x,y:dragOrigin.y+p.y-mouseOrigin.y))
    }
    override func mouseUp(with event:NSEvent) { keepOnScreen() }
    func keepOnScreen() {
        guard let win=window,let screen=win.screen ?? NSScreen.main else{return}
        let a=screen.visibleFrame,f=win.frame
        win.setFrameOrigin(NSPoint(x:max(a.minX,min(f.minX,a.maxX-f.width)),y:max(a.minY,min(f.minY,a.maxY-f.height))))
    }
    func makeMenu() -> NSMenu {
        let menu=NSMenu(title:"PawLink")
        let heading=menu.addItem(withTitle:"选择猫咪动作",action:nil,keyEquivalent:"");heading.isEnabled=false
        for i in motions.indices {
            let item=menu.addItem(withTitle:motions[i].name,action:#selector(choose(_:)),keyEquivalent:"")
            item.tag=i;item.target=self;item.state=i==selected ? .on : .off
        }
        menu.addItem(.separator())
        let pause=menu.addItem(withTitle:paused ? "继续播放" : "暂停动作",action:#selector(togglePause),keyEquivalent:"");pause.target=self
        let sizeItem=menu.addItem(withTitle:"宠物大小",action:nil,keyEquivalent:"")
        let sizes=NSMenu()
        for (label,size) in [("小",220),("中",300),("大",400)] {
            let item=sizes.addItem(withTitle:label,action:#selector(resize(_:)),keyEquivalent:"");item.tag=size;item.target=self
            item.state=Int(bounds.width)==size ? .on : .off
        }
        sizeItem.submenu=sizes
        menu.addItem(.separator())
        let apiInfo=menu.addItem(withTitle:api?.status ?? "API 未启动",action:nil,keyEquivalent:"");apiInfo.isEnabled=false
        let quit=menu.addItem(withTitle:"退出桌面宠物",action:#selector(quitPet),keyEquivalent:"");quit.target=self
        return menu
    }
    override func rightMouseDown(with event:NSEvent) { NSMenu.popUpContextMenu(makeMenu(),with:event,for:self) }
    @objc func choose(_ sender:NSMenuItem) { selected=sender.tag;elapsed=0;paused=false;updateLabel();needsDisplay=true;api?.changed(source:"manual") }
    @objc func togglePause() { paused.toggle();api?.changed(source:"manual") }
    @objc func resize(_ sender:NSMenuItem) { window?.setContentSize(NSSize(width:sender.tag,height:sender.tag));keepOnScreen();needsDisplay=true }
    @objc func quitPet() { NSApp.terminate(nil) }
}
class AppDelegate: NSObject,NSApplicationDelegate {
    var window: PetWindow!
    var status: NSStatusItem!
    let pet=PetView(frame:CGRect(x:0,y:0,width:300,height:300))
    func applicationDidFinishLaunching(_ notification: Notification) {
        do { try pet.prepare() } catch {
            let alert=NSAlert();alert.messageText="桌面宠物启动失败";alert.informativeText=error.localizedDescription;alert.runModal();NSApp.terminate(nil);return
        }
        let visible=NSScreen.main!.visibleFrame
        window=PetWindow(contentRect:CGRect(x:visible.maxX-330,y:visible.minY+30,width:300,height:300),styleMask:[.borderless,.nonactivatingPanel],backing:.buffered,defer:false)
        window.title="PawLink 桌面宠物";window.isOpaque=false;window.backgroundColor = .clear
        window.hasShadow=false;window.level = .floating;window.hidesOnDeactivate=false
        window.collectionBehavior=[.canJoinAllSpaces,.fullScreenAuxiliary];window.contentView=pet
        window.orderFrontRegardless()
        pet.api = PetAPI(pet:pet)
        do { try pet.api?.start() } catch { pet.api?.status = "API 无法启动：\(error.localizedDescription)" }
        status=NSStatusBar.system.statusItem(withLength:NSStatusItem.squareLength)
        status.button?.image=NSImage(systemSymbolName:"pawprint.fill",accessibilityDescription:"PawLink 桌面宠物")
        status.button?.target=self;status.button?.action=#selector(openMenu)
    }
    @objc func openMenu() { status.menu=pet.makeMenu();status.button?.performClick(nil);status.menu=nil }
}
let app=NSApplication.shared
app.setActivationPolicy(.accessory)
let delegate=AppDelegate()
app.delegate=delegate
app.run()
