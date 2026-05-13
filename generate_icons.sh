#!/bin/bash
ICON_SOURCE="app-icons/59b835bc-f01f-4076-a404-812617fdf241.png"
RES_DIR="app/src/main/res"

# Function to resize
generate() {
    size=$1
    folder=$2
    mkdir -p "$RES_DIR/$folder"
    convert "$ICON_SOURCE" -resize "${size}x${size}" "$RES_DIR/$folder/ic_launcher.png"
    convert "$ICON_SOURCE" -resize "${size}x${size}" "$RES_DIR/$folder/ic_launcher_round.png"
}

generate 48 mipmap-mdpi
generate 72 mipmap-hdpi
generate 96 mipmap-xhdpi
generate 144 mipmap-xxhdpi
generate 192 mipmap-xxxhdpi

echo "Icons generated successfully."
