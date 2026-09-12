import AVKit
import SwiftUI

struct AudioRoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.prioritizesVideoDevices = false
        view.tintColor = .secondaryLabel
        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
