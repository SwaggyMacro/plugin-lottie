export interface LottieConfig { width: number; height: number; autoplay: boolean; loop: boolean; speed: number; fit: string; align: string; controls: boolean; hoverPlay: boolean; freezeOnOffscreen: boolean; ariaLabel: string }

export interface LottieInsertAttributes extends LottieConfig {
  name: string
  src: string
  format: string
}
